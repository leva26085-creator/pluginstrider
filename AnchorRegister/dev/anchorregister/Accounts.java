package dev.anchorregister;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Accounts {
    private static final long SESSION_MILLIS = Duration.ofHours(5L).toMillis();
    private static final int ITERATIONS = 600000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final String FILE_PREFIX = "user-";
    private static final String FILE_SUFFIX = ".properties";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,16}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;
    private final Map<String, Account> accounts = new HashMap<String, Account>();

    /*
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    Accounts(Path directory) throws IOException {
        this.directory = Files.createDirectories(directory, new FileAttribute[0]);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "user-*.properties");){
            for (Path file : files) {
                String filename = file.getFileName().toString();
                String name = filename.substring(FILE_PREFIX.length(), filename.length() - FILE_SUFFIX.length());
                try {
                    InputStream input = Files.newInputStream(file, new OpenOption[0]);
                    try {
                        Properties data = new Properties();
                        data.load(input);
                        this.accounts.put(name, Accounts.read(name, data));
                    }
                    finally {
                        if (input == null) continue;
                        input.close();
                    }
                }
                catch (IllegalArgumentException | NullPointerException exception) {
                    throw new IOException("\u041d\u0435\u043a\u043e\u0440\u0440\u0435\u043a\u0442\u043d\u044b\u0439 \u0444\u0430\u0439\u043b \u0430\u043a\u043a\u0430\u0443\u043d\u0442\u0430 " + filename, exception);
                }
            }
        }
    }

    static String nameKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    static String ipKey(InetAddress address) {
        return Accounts.encode(address.getAddress());
    }

    Account get(String name) {
        return this.accounts.get(name);
    }

    void save(String name, Password password, String ip, long now) throws IOException {
        Account account = new Account(password, ip, now);
        Properties data = new Properties();
        data.setProperty("version", "1");
        data.setProperty("salt", password.salt());
        data.setProperty("hash", password.hash());
        data.setProperty("ip", ip);
        data.setProperty("authenticated-at", Long.toString(now));
        Path destination = this.directory.resolve(FILE_PREFIX + name + FILE_SUFFIX);
        Path temporary = Files.createTempFile(this.directory, FILE_PREFIX, ".tmp", new FileAttribute[0]);
        try {
            try (OutputStream output = Files.newOutputStream(temporary, new OpenOption[0]);){
                data.store(output, null);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            }
            catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        this.accounts.put(name, account);
    }

    private static Account read(String name, Properties data) {
        if (!Accounts.validName(name) || !"1".equals(data.getProperty("version"))) {
            throw new IllegalArgumentException("\u041d\u0435\u0434\u043e\u043f\u0443\u0441\u0442\u0438\u043c\u043e\u0435 \u0438\u043c\u044f \u0438\u043b\u0438 \u0432\u0435\u0440\u0441\u0438\u044f \u0430\u043a\u043a\u0430\u0443\u043d\u0442\u0430.");
        }
        String salt = data.getProperty("salt");
        String hash = data.getProperty("hash");
        String ip = data.getProperty("ip");
        long timestamp = Long.parseLong(data.getProperty("authenticated-at"));
        int ipLength = Accounts.decode(ip).length;
        if (Accounts.decode(salt).length != 16 || Accounts.decode(hash).length != 32 || ipLength != 4 && ipLength != 16 || timestamp <= 0L) {
            throw new IllegalArgumentException("\u041d\u0435\u043a\u043e\u0440\u0440\u0435\u043a\u0442\u043d\u044b\u0435 \u0434\u0430\u043d\u043d\u044b\u0435 \u0430\u043a\u043a\u0430\u0443\u043d\u0442\u0430.");
        }
        return new Account(new Password(salt, hash), ip, timestamp);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 600000, 256);
        try {
            byte[] byArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return byArray;
        }
        finally {
            spec.clearPassword();
            Arrays.fill(password, '\u0000');
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }

    record Account(Password password, String ip, long authenticatedAt) {
        boolean remembers(String currentIp, long now) {
            return this.ip.equals(currentIp) && now >= this.authenticatedAt && now - this.authenticatedAt < SESSION_MILLIS;
        }
    }

    record Password(String salt, String hash) {
        static Password create(char[] password) throws GeneralSecurityException {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            return new Password(Accounts.encode(salt), Accounts.encode(Accounts.derive(password, salt)));
        }

        boolean matches(char[] password) throws GeneralSecurityException {
            return MessageDigest.isEqual(Accounts.decode(this.hash), Accounts.derive(password, Accounts.decode(this.salt)));
        }
    }
}

