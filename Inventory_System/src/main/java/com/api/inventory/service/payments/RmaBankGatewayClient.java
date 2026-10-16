package com.api.inventory.service.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The REAL connection to the RMA Payment Gateway (BFS Secure, the merchant API), app.payments.bank.mode=rma.
 * Money moves: the customer's bank debits the account and pays DP DrukBazaars' merchant account.
 *
 * Messages (form fields, answers come back as name=value pairs):
 *   AR  Authorisation Request  our order number + amount             -> AC with bfs_bfsTxnId (the gateway's transaction)
 *   AE  Account Enquiry        bank id + account number              -> EC; the bank texts the one-time code (OTP)
 *   DR  Debit Request          the OTP                               -> AC with bfs_debitAuthCode 00 and bfs_debitAuthNo
 *                                                                        (the bank's journal number for the debit)
 * Every message carries bfs_checkSum: the values of all the other bfs_ fields, sorted by field name and joined with
 * "|", signed SHA1withRSA with DP DrukBazaars' private key, in upper-case hex. RMA's answers are checked the same way with
 * RMA's public key when it is configured.
 *
 * Settings (all from the merchant kit RMA gives after registration; see DEPLOY.md):
 *   app.payments.bank.rma.url                    the gateway address (RMA's test (UAT) address first, then the live one)
 *   app.payments.bank.rma.beneficiary-id         DP DrukBazaars' merchant (beneficiary) id, bfs_benfId
 *   app.payments.bank.rma.beneficiary-bank-code  bfs_benfBankCode (01 unless RMA says otherwise)
 *   app.payments.bank.rma.private-key            path to DP DrukBazaars' private key (PEM, PKCS#8)
 *   app.payments.bank.rma.public-key             path to RMA's public key or certificate (PEM), to check answers
 *   app.payments.bank.banks                      the banks with RMA's bank ids
 * Field names and answer codes follow the BFS Secure merchant API; check them against the kit's message specification
 * and run every case on RMA's test address before switching the live site to it.
 *
 * Never logged: the account number, the OTP, or whole messages.
 */
@Component
@ConditionalOnProperty(name = "app.payments.bank.mode", havingValue = "rma")
public class RmaBankGatewayClient implements BankGatewayClient {

    /** Sends one message to the gateway and gives back its answer fields. Replaced by a pretend RMA in tests. */
    public interface Transport {
        Map<String, String> post(URI url, Map<String, String> fields) throws IOException, InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(RmaBankGatewayClient.class);
    private static final DateTimeFormatter TXN_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Thimphu"));
    private static final String OK = "00";

    private final URI url;
    private final String beneficiaryId;
    private final String beneficiaryBankCode;
    private final String version;
    private final List<Bank> banks;
    private final PrivateKey merchantKey;
    private final PublicKey rmaKey;
    private final Transport transport;

    @Autowired
    public RmaBankGatewayClient(@Value("${app.payments.bank.rma.url:}") String url,
                                @Value("${app.payments.bank.rma.beneficiary-id:}") String beneficiaryId,
                                @Value("${app.payments.bank.rma.beneficiary-bank-code:01}") String beneficiaryBankCode,
                                @Value("${app.payments.bank.rma.private-key:}") String privateKeyPath,
                                @Value("${app.payments.bank.rma.public-key:}") String publicKeyPath,
                                @Value("${app.payments.bank.rma.version:1.0}") String version,
                                @Value("${app.payments.bank.rma.timeout:30s}") Duration timeout,
                                @Value("${app.payments.bank.banks}") String bankList) {
        this(url, beneficiaryId, beneficiaryBankCode, readPrivateKey(privateKeyPath),
                publicKeyPath == null || publicKeyPath.isBlank() ? null : readPublicKey(publicKeyPath),
                version, Bank.parse(bankList), httpTransport(timeout));
    }

    /** For tests: keys and the transport given directly. */
    public RmaBankGatewayClient(String url, String beneficiaryId, String beneficiaryBankCode, PrivateKey merchantKey,
                                PublicKey rmaKey, String version, List<Bank> banks, Transport transport) {
        if (url == null || !url.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("app.payments.bank.rma.url must be the RMA gateway's https:// address from the merchant kit.");
        }
        if (beneficiaryId == null || beneficiaryId.isBlank()) {
            throw new IllegalStateException("app.payments.bank.rma.beneficiary-id is missing: DP DrukBazaars' merchant id from RMA.");
        }
        if (banks.isEmpty()) {
            throw new IllegalStateException("app.payments.bank.banks is empty: list the banks with RMA's bank ids.");
        }
        this.url = URI.create(url.trim());
        this.beneficiaryId = beneficiaryId.trim();
        this.beneficiaryBankCode = beneficiaryBankCode.trim();
        this.merchantKey = merchantKey;
        this.rmaKey = rmaKey;
        this.version = version.trim();
        this.banks = banks;
        this.transport = transport;
        if (rmaKey == null) {
            throw new IllegalStateException(
                    "RMA public key is required. " +
                            "Configure app.payments.bank.rma.public-key " +
                            "before enabling real payments."
            );
        }
    }

    @Override
    public boolean testMode() {
        return false;
    }

    @Override
    public List<Bank> banks() {
        return banks;
    }

    @Override
    public String start(String reference, BigDecimal amount, String description, String customerEmail) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("bfs_msgType", "AR");
        f.put("bfs_benfTxnTime", TXN_TIME.format(Instant.now()));
        f.put("bfs_orderNo", reference);
        f.put("bfs_benfId", beneficiaryId);
        f.put("bfs_benfBankCode", beneficiaryBankCode);
        f.put("bfs_txnCurrency", "BTN");
        f.put("bfs_txnAmount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        f.put("bfs_remitterEmail", customerEmail == null ? "" : customerEmail);
        f.put("bfs_paymentDesc", ascii(description, 100));
        f.put("bfs_version", version);
        Map<String, String> answer;
        try {
            answer = send(f);
        } catch (IOException | InterruptedException | GeneralSecurityException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("RMA AR for {} failed: {}", reference, e.toString());
            throw new IllegalStateException("The payment gateway could not be reached. Nothing was taken; please try again in a moment.");
        }
        String txnId = answer.get("bfs_bfsTxnId");
        if (!OK.equals(answer.get("bfs_responseCode")) || txnId == null || txnId.isBlank()) {
            throw new IllegalStateException("The payment gateway did not accept the payment" + because(answer) + ". Nothing was taken.");
        }
        return txnId.trim();
    }

    @Override
    public Answer requestCode(String gatewayTransactionId, String bankCode, String accountNumber) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("bfs_msgType", "AE");
        f.put("bfs_benfId", beneficiaryId);
        f.put("bfs_bfsTxnId", gatewayTransactionId);
        f.put("bfs_remitterBankId", bankCode);
        f.put("bfs_remitterAccNo", accountNumber);
        Map<String, String> answer;
        try {
            answer = send(f);
        } catch (IOException | InterruptedException | GeneralSecurityException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("RMA AE for {} failed: {}", gatewayTransactionId, e.toString());
            return Answer.no("GATEWAY_ERROR", "Your bank could not be reached. Nothing was taken; please try again.");
        }
        if (OK.equals(answer.get("bfs_responseCode"))) {
            return Answer.ok("Your bank sent a code by text message to the phone registered with this account.");
        }
        String why = lower(answer.get("bfs_responseDesc"));
        if (why.contains("account") || why.contains("remitter")) {
            return Answer.no("ACCOUNT_NOT_FOUND", "Your bank could not use this account number" + because(answer) + ". Check it and try again.");
        }
        return Answer.no("REFUSED", "Your bank refused" + because(answer) + ".");
    }

    @Override
    public Answer debit(String gatewayTransactionId, String code) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("bfs_msgType", "DR");
        f.put("bfs_benfId", beneficiaryId);
        f.put("bfs_bfsTxnId", gatewayTransactionId);
        f.put("bfs_remitterOtp", code);
        Map<String, String> answer;
        try {
            answer = send(f);
        } catch (IOException | InterruptedException | GeneralSecurityException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // the debit may or may not have happened: never "failed", never "try again"
            log.warn("RMA DR for {} got no usable answer: {}", gatewayTransactionId, e.toString());
            return Answer.no("NO_ANSWER", "The bank's answer did not arrive.");
        }
        String authCode = answer.getOrDefault("bfs_debitAuthCode", answer.get("bfs_responseCode"));
        if (OK.equals(authCode)) {
            String journal = answer.get("bfs_debitAuthNo");
            return Answer.ok("Paid", journal == null || journal.isBlank() ? null : journal.trim());
        }
        String why = lower(answer.get("bfs_responseDesc"));
        if (why.contains("otp") && why.contains("expire")) {
            return Answer.no("CODE_EXPIRED", "The code has expired.");
        }
        if (why.contains("otp")) {
            return Answer.no("WRONG_CODE", "That code is not right.");
        }
        if (why.contains("insufficient") || why.contains("balance")) {
            return Answer.no("INSUFFICIENT_FUNDS", "Your bank refused the payment: there is not enough money in the account.");
        }
        return Answer.no("REFUSED", "Your bank refused the payment" + because(answer) + ". Nothing was taken.");
    }

    // ================= The messages =================

    private Map<String, String> send(Map<String, String> fields) throws IOException, InterruptedException, GeneralSecurityException {
        Map<String, String> signed = new LinkedHashMap<>(fields);
        signed.put("bfs_checkSum", sign(fields, merchantKey));
        Map<String, String> answer = transport.post(url, signed);
        if (answer == null) {
            throw new GeneralSecurityException("RMA returned no response.");
        }

        String checksum = answer.get("bfs_checkSum");

        Map<String, String> rest = new TreeMap<>(answer);
        rest.remove("bfs_checkSum");

        if (checksum == null || !verify(rest, checksum, rmaKey)) {
            throw new GeneralSecurityException(
                    "RMA's answer failed its signature check"
            );
        }
        log.info("RMA {} {} -> {} {}", fields.get("bfs_msgType"), fields.getOrDefault("bfs_bfsTxnId", fields.get("bfs_orderNo")),
                answer.get("bfs_msgType"), answer.getOrDefault("bfs_debitAuthCode", answer.get("bfs_responseCode")));
        return answer;
    }

    /** The values of the bfs_ fields sorted by name, joined with "|". */
    public static String sourceString(Map<String, String> fields) {
        StringJoiner joined = new StringJoiner("|");
        new TreeMap<>(fields).forEach((name, value) -> {
            if (name.startsWith("bfs_") && !"bfs_checkSum".equals(name)) {
                joined.add(value == null ? "" : value);
            }
        });
        return joined.toString();
    }

    public static String sign(Map<String, String> fields, PrivateKey key) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA1withRSA");
        s.initSign(key);
        s.update(sourceString(fields).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().withUpperCase().formatHex(s.sign());
    }

    public static boolean verify(Map<String, String> fields, String checksumHex, PublicKey key) {
        try {
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initVerify(key);
            s.update(sourceString(fields).getBytes(StandardCharsets.UTF_8));
            return s.verify(HexFormat.of().parseHex(checksumHex.trim().toLowerCase(Locale.ROOT)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** name=value&name=value, URL-encoded (how the gateway answers). */
    public static Map<String, String> parseAnswer(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.trim().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static Transport httpTransport(Duration timeout) {
        return new Transport() {
            private HttpClient client; // made on first use

            @Override
            public synchronized Map<String, String> post(URI url, Map<String, String> fields) throws IOException, InterruptedException {
                if (client == null) {
                    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                }
                StringJoiner form = new StringJoiner("&");
                fields.forEach((k, v) -> form.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
                HttpRequest request = HttpRequest.newBuilder(url).timeout(timeout)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                return parseAnswer(response.body());
            }
        };
    }

    // ================= Keys =================

    static PrivateKey readPrivateKey(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("app.payments.bank.rma.private-key is missing: the path to DP DrukBazaars' private key (PEM).");
        }
        try {
            String pem = Files.readString(Path.of(path.trim()));
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                throw new IllegalStateException("The private key is in the old PKCS#1 format. Convert it once with: "
                        + "openssl pkcs8 -topk8 -nocrypt -in merchant.key -out merchant-pkcs8.pem");
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pemBody(pem)));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not read the private key at " + path + ": " + e.getMessage());
        }
    }

    static PublicKey readPublicKey(String path) {
        try {
            String pem = Files.readString(Path.of(path.trim()));
            if (pem.contains("BEGIN CERTIFICATE")) {
                return CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))).getPublicKey();
            }
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pemBody(pem)));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not read RMA's public key at " + path + ": " + e.getMessage());
        }
    }

    private static byte[] pemBody(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
    }

    // ================= Small helpers =================

    private static String because(Map<String, String> answer) {
        String desc = answer.get("bfs_responseDesc");
        return desc == null || desc.isBlank() ? "" : " (" + desc.trim() + ")";
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String ascii(String s, int max) {
        String clean = s == null ? "" : s.replaceAll("[^\\x20-\\x7E]", "").replace("|", "/");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
