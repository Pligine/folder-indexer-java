import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {

    // Внутренний класс для хранения информации о файле
    static class FileRecord {
        String path;
        long size;
        String hash;
        long lastModified;

        public FileRecord(String path, long size, String hash, long lastModified) {
            this.path = path;
            this.size = size;
            this.hash = hash;
            this.lastModified = lastModified;
        }
    }

    public static void main(String[] args) {
        // Если путь не передан в аргументах, спрашиваем его или берем текущую папку
        String targetDir = args.length > 0 ? args[0] : ".";
        File indexFile = new File("index.csv");

        System.out.println("=== Консольный индексатор папок ===");
        System.out.println("Сканируем папку: " + new File(targetDir).getAbsolutePath());

        // 1. Загружаем старый индекс (для проверки изменений/бэкапов)
        Map<String, FileRecord> oldIndex = loadIndex(indexFile);

        // 2. Сканируем папку и строим новый индекс
        List<FileRecord> newIndex = new ArrayList<>();
        try {
            newIndex = scanDirectory(targetDir);
        } catch (IOException e) {
            System.err.println("Ошибка при сканировании папки: " + e.getMessage());
            return;
        }

        // 3. Ищем дубликаты
        findDuplicates(newIndex);

        // 4. Проверяем изменения (сравнение с прошлым запуском)
        checkChanges(oldIndex, newIndex);

        // 5. Сохраняем новый индекс в файл для следующего запуска
        saveIndex(indexFile, newIndex);
        System.out.println("\nИндекс успешно сохранен в " + indexFile.getName());
    }

    // --- МЕТОД СКанирования ---
    private static List<FileRecord> scanDirectory(String dirPath) throws IOException {
        List<FileRecord> records = new ArrayList<>();
        Path startPath = Paths.get(dirPath);

        try (Stream<Path> paths = Files.walk(startPath)) {
            List<Path> fileList = paths.filter(Files::isRegularFile).collect(Collectors.toList());
            
            System.out.println("Найдено файлов для анализа: " + fileList.size());

            for (Path path : fileList) {
                try {
                    long size = Files.size(path);
                    long lastModified = Files.getLastModifiedTime(path).toMillis();
                    String hash = calculateHash(path);
                    
                    records.add(new FileRecord(path.toString(), size, hash, lastModified));
                } catch (Exception e) {
                    // Пропускаем файлы, к которым нет доступа или которые заблокированы
                }
            }
        }
        return records;
    }

    // --- МЕТОД ПОИСКА ДУБЛИКАТОВ ---
    private static void findDuplicates(List<FileRecord> records) {
        System.out.println("\n--- Поиск дубликатов ---");
        
        // Группируем по хешу
        Map<String, List<FileRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(r -> r.hash));

        boolean found = false;
        for (Map.Entry<String, List<FileRecord>> entry : grouped.entrySet()) {
            if (entry.getValue().size() > 1) {
                found = true;
                System.out.println("Найдены дубликаты (хеш: " + entry.getKey().substring(0, 10) + "...):");
                for (FileRecord r : entry.getValue()) {
                    System.out.println("  - " + r.path + " (размер: " + r.size + " байт)");
                }
            }
        }
        if (!found) {
            System.out.println("Дубликатов не найдено.");
        }
    }

    // --- МЕТОД ПРОВЕРКИ ИЗМЕНЕНИЙ (РЕЗЕРВНЫХ КОПИЙ) ---
    private static void checkChanges(Map<String, FileRecord> oldIndex, List<FileRecord> newIndex) {
        System.out.println("\n--- Проверка изменений (сравнение с прошлым запуском) ---");
        if (oldIndex.isEmpty()) {
            System.out.println("Это первый запуск, сравнивать не с чем. Данные сохранены для будущего сравнения.");
            return;
        }

        int changed = 0;
        int deleted = 0;

        // Проверяем, что изменилось или удалилось
        for (FileRecord oldRec : oldIndex.values()) {
            FileRecord newRec = newIndex.stream()
                    .filter(r -> r.path.equals(oldRec.path))
                    .findFirst().orElse(null);

            if (newRec == null) {
                System.out.println("УДАЛЕН: " + oldRec.path);
                deleted++;
            } else if (!newRec.hash.equals(oldRec.hash)) {
                System.out.println("ИЗМЕНЕН: " + oldRec.path);
                changed++;
            }
        }

        if (changed == 0 && deleted == 0) {
            System.out.println("Никаких изменений не обнаружено.");
        } else {
            System.out.println("Итого: изменено " + changed + ", удалено " + deleted + " файлов.");
        }
    }

    // --- ВЫЧИСЛЕНИЕ ХЕША (SHA-256) ---
    private static String calculateHash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // --- РАБОТА С ФАЙЛОМ ИНДЕКСА (ВМЕСТО БАЗЫ ДАННЫХ) ---
    private static Map<String, FileRecord> loadIndex(File file) {
        Map<String, FileRecord> map = new HashMap<>();
        if (!file.exists()) return map;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|\\|\\|");
                if (parts.length == 4) {
                    FileRecord rec = new FileRecord(parts[0], Long.parseLong(parts[1]), parts[2], Long.parseLong(parts[3]));
                    map.put(rec.path, rec);
                }
            }
        } catch (Exception e) {
            System.err.println("Не удалось загрузить старый индекс.");
        }
        return map;
    }

    private static void saveIndex(File file, List<FileRecord> records) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            for (FileRecord rec : records) {
                bw.write(rec.path + "|||" + rec.size + "|||" + rec.hash + "|||" + rec.lastModified);
                bw.newLine();
            }
        } catch (IOException e) {
            System.err.println("Не удалось сохранить индекс.");
        }
    }
}
