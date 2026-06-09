import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileIndexer {

    private static final String DB_URL = "jdbc:sqlite:file_index.db";
    private Connection conn = null;

    public static void main(String[] args) {
        FileIndexer indexer = new FileIndexer();
        if (args.length == 0) {
            System.out.println("Usage: java FileIndexer <command> [path]");
            System.out.println("Commands:");
            System.out.println("  index <path>          - Index files in path");
            System.out.println("  find-duplicates       - Find duplicate files");
            System.out.println("  verify-backups <path> - Verify backups against indexed files");
            return;
        }

        String command = args[0];
        switch (command.toLowerCase()) {
            case "index":
                if (args.length < 2) {
                    System.out.println("Path required for indexing.");
                    return;
                }
                indexer.connect();
                indexer.createTable();
                indexer.indexPath(args[1]);
                indexer.disconnect();
                break;
            case "find-duplicates":
                indexer.connect();
                indexer.findDuplicates();
                indexer.disconnect();
                break;
            case "verify-backups":
                if (args.length < 2) {
                    System.out.println("Path required for backup verification.");
                    return;
                }
                indexer.connect();
                indexer.verifyBackups(args[1]);
                indexer.disconnect();
                break;
            default:
                System.out.println("Unknown command: " + command);
        }
    }

    private void connect() {
        try {
            conn = DriverManager.getConnection(DB_URL);
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    private void disconnect() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    private void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT NOT NULL UNIQUE,
                size INTEGER NOT NULL,
                modified_time INTEGER NOT NULL,
                hash TEXT NOT NULL
            );
            """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    private String computeSHA256Hash(Path filePath) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] data = Files.readAllBytes(filePath);
        byte[] hashBytes = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void indexPath(String rootPathStr) {
        Path rootPath = Paths.get(rootPathStr).toAbsolutePath().normalize();
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            System.out.println("Invalid directory path: " + rootPathStr);
            return;
        }

        try {
            Files.walk(rootPath)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            long size = Files.size(file);
                            long modTime = Files.getLastModifiedTime(file).toMillis();
                            String hash = computeSHA256Hash(file);

                            // Проверяем, существует ли уже запись
                            String checkSql = "SELECT id FROM files WHERE file_path = ?";
                            boolean exists = false;
                            try (PreparedStatement psCheck = conn.prepareStatement(checkSql)) {
                                psCheck.setString(1, file.toString());
                                ResultSet rs = psCheck.executeQuery();
                                exists = rs.next();
                            }

                            if (exists) {
                                // Обновляем запись
                                String updateSql = "UPDATE files SET size = ?, modified_time = ?, hash = ? WHERE file_path = ?";
                                try (PreparedStatement psUpdate = conn.prepareStatement(updateSql)) {
                                    psUpdate.setLong(1, size);
                                    psUpdate.setLong(2, modTime);
                                    psUpdate.setString(3, hash);
                                    psUpdate.setString(4, file.toString());
                                    psUpdate.executeUpdate();
                                }
                            } else {
                                // Вставляем новую запись
                                String insertSql = "INSERT INTO files (file_path, size, modified_time, hash) VALUES (?, ?, ?, ?)";
                                try (PreparedStatement psInsert = conn.prepareStatement(insertSql)) {
                                    psInsert.setString(1, file.toString());
                                    psInsert.setLong(2, size);
                                    psInsert.setLong(3, modTime);
                                    psInsert.setString(4, hash);
                                    psInsert.executeUpdate();
                                }
                            }
                        } catch (IOException | SQLException | NoSuchAlgorithmException e) {
                            System.err.println("Error processing file: " + file + ", Error: " + e.getMessage());
                        }
                    });
            System.out.println("Indexing completed for: " + rootPathStr);
        } catch (IOException e) {
            System.err.println("Error walking directory: " + e.getMessage());
        }
    }


    private void findDuplicates() {
        String sql = "SELECT hash, GROUP_CONCAT(file_path) as paths FROM files GROUP BY hash HAVING COUNT(*) > 1;";
        try (PreparedStatement pstmt = conn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
            boolean found = false;
            while (rs.next()) {
                found = true;
                String hash = rs.getString("hash");
                String pathsList = rs.getString("paths");
                System.out.println("\nDuplicate group (Hash: " + hash + "):");
                for (String path : pathsList.split(",")) {
                    System.out.println("  - " + path.trim());
                }
            }
            if (!found) {
                System.out.println("No duplicates found.");
            }
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    private void verifyBackups(String backupPathStr) {
        Path backupRoot = Paths.get(backupPathStr).toAbsolutePath().normalize();
        if (!Files.exists(backupRoot) || !Files.isDirectory(backupRoot)) {
            System.out.println("Invalid backup directory path: " + backupPathStr);
            return;
        }

        // Загружаем все файлы из индекса в Map по хешу
        Map<String, List<String>> originalFilesByHash = new HashMap<>();
        String loadOriginalsSql = "SELECT hash, file_path FROM files;";
        try (PreparedStatement pstmt = conn.prepareStatement(loadOriginalsSql); ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String hash = rs.getString("hash");
                String path = rs.getString("file_path");
                originalFilesByHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(path);
            }
        } catch (SQLException e) {
            System.err.println("Error loading original files from DB: " + e.getMessage());
            return;
        }

        System.out.println("\nVerifying backups in: " + backupPathStr);

        try {
            Files.walk(backupRoot)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            String hash;
                            try {
                                hash = computeSHA256Hash(file);
                            } catch (NoSuchAlgorithmException ex) {
                                System.err.println("Hash algorithm error for file: " + file + ", Error: " + ex.getMessage());
                                return; // Пропустить этот файл
                            }

                            List<String> originals = originalFilesByHash.get(hash);
                            if (originals != null && !originals.isEmpty()) {
                                System.out.println("OK: " + file + " matches original(s):");
                                originals.forEach(originalPath -> System.out.println("    - " + originalPath));
                            } else {
                                System.out.println("MISSING: " + file + " (no matching original file found)");
                            }
                        } catch (IOException e) {
                            System.err.println("Error processing backup file: " + file + ", Error: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error walking backup directory: " + e.getMessage());
        }
    }
}