package org.vaadin.filebrowser.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.vaadin.filebrowser.storage.remote.Fingerprints;

/** Keys and certificates made for one test run, so none are committed. */
final class TestKeys {

    /** @param privateKey OpenSSH format, as an admin would paste it; {@code publicKey} as in authorized_keys */
    record SshKey(String privateKey, String publicKey) {
    }

    /** PEM files for a server, and the fingerprint the app shows for the certificate. */
    record Certificate(Path cert, Path key, String fingerprint) {
    }

    private TestKeys() {
    }

    static SshKey ed25519() {
        try {
            // sshd writes ed25519 keys only in BouncyCastle's form
            final var pair = KeyPairGenerator.getInstance("Ed25519", new BouncyCastleProvider()).generateKeyPair();
            final var out = new ByteArrayOutputStream();
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "file-browser test", null, out);
            return new SshKey(out.toString(StandardCharsets.UTF_8), PublicKeyEntry.toString(pair.getPublic()));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A self-signed certificate for localhost, valid for a day. */
    static Certificate selfSigned(Path dir) {
        try {
            final var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            final var pair = generator.generateKeyPair();
            final var name = new X500Name("CN=localhost");
            final var now = Instant.now();
            final var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()),
                    Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(Duration.ofDays(1))), name,
                    pair.getPublic());
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
            final var holder = builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()));
            final var cert = dir.resolve("cert.pem");
            final var key = dir.resolve("key.pem");
            Files.writeString(cert, pem(holder));
            Files.writeString(key, pem(pair.getPrivate()));
            return new Certificate(cert, key, Fingerprints.sha256(holder.getEncoded()));
        } catch (IOException | GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(Object value) throws IOException {
        final var text = new StringWriter();
        try (final var writer = new JcaPEMWriter(text)) {
            writer.writeObject(value);
        }
        return text.toString();
    }
}
