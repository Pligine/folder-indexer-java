import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public class Main {

    static class FileRecord {
        String path;
        long size;
        String hash;
        long lastModified;

        FileRecord(String path, long size, String hash, long lastModified) {
            this.path = path;
            this.size = size;
            this.hash = hash;
            this.lastModified = lastModified;
        }
    }

    private static final File INDEX_FILE = new File("index.csv");
    private static List<FileRecord> lastScan = new ArrayList<>();
    private static String lastScanPath = "";

    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);

        System.out.println("=== Индексатор папок ===");

        int choice = -1;
        while (choice != 0) {
            System.out.println();
            System.out.println("1 — Проиндексировать папку");
            System.out.println("2 — Найти дубликаты");
            System.out.println("3 — История сканирований");
            System.out.println("0 — Выход");
            System.out.print("Выбор: ");

            try {
                choice = Integer.parseInt(input.nextLine().trim());

                if (choice == 1) {
                    scanFolder(input);
                } else if (choice == 2) {
                    findDuplicates();
                } else if (choice == 3) {
                    showHistory();
                } else if (choice != 0) {
                    System.out.println("Нет такого пункта.");
                }
            } catch (Exception e) {
                System.out.println("Ошибка: " + e.getMessage());
            }
        }

        System.out.println("До свидания.");
        input.close();
    }

    private static void scanFolder(Scanner input) throws Exception {
        System.out.print("Путь к папке: ");
        String path = input.nextLine().trim();
        File folder = new File(path);

        if (!folder.isDirectory()) {
            System.out.println("Это не папка.");
            return;
        }

        System.out.println("Сканирование...");
        Map<String, FileRecord> oldIndex = loadIndex(INDEX_FILE);
        lastScan = scanDirectory(path);
        lastScanPath = folder.getAbsolutePath();

        checkChanges(oldIndex, lastScan);
        saveIndex(INDEX_FILE, lastScan);

        System.out.println("Готово. Файлов: " + lastScan.size());
        System.out.println("Индекс сохранён в " + INDEX_FILE.getName());
    }

    private static void findDuplicates() throws Exception {
        List<FileRecord> records = getRecordsForSearch();
        if (records.isEmpty()) {
            System.out.println("Сначала проиндексируйте папку.");
            return;
        }

        HashMap<String, ArrayList<FileRecord>> groups = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            FileRecord rec = records.get(i);
            if (!groups.containsKey(rec.hash)) {
                groups.put(rec.hash, new ArrayList<>());
            }
            groups.get(rec.hash).add(rec);
        }

        int groupNum = 0;
        for (String hash : groups.keySet()) {
            ArrayList<FileRecord> group = groups.get(hash);
            if (group.size() >= 2) {
                groupNum++;
                System.out.println("Группа " + groupNum + ":");
                for (int i = 0; i < group.size(); i++) {
                    System.out.println("  " + group.get(i).path);
                }
            }
        }

        if (groupNum == 0) {
            System.out.println("Дубликаты не найдены.");
        }
    }

    private static void showHistory() {
        if (!INDEX_FILE.exists()) {
            System.out.println("Сканирований пока нет.");
            return;
        }

        List<FileRecord> records = loadIndexList(INDEX_FILE);
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        String date = format.format(new Date(INDEX_FILE.lastModified()));

        System.out.printf("%-4s %-20s  %s%n", "ID", "Дата", "Папка");
        System.out.println("--------------------------------------------------------------");

        if (lastScanPath.isEmpty() && !records.isEmpty()) {
            lastScanPath = new File(records.get(0).path).getParent();
        }

        System.out.printf("#%-3d %-20s  %s (%d файлов)%n", 1, date, lastScanPath, records.size());
    }

    private static List<FileRecord> getRecordsForSearch() {
        if (!lastScan.isEmpty()) {
            return lastScan;
        }
        if (INDEX_FILE.exists()) {
            return loadIndexList(INDEX_FILE);
        }
        return new ArrayList<>();
    }

    private static List<FileRecord> scanDirectory(String dirPath) throws Exception {
        ArrayList<FileRecord> records = new ArrayList<>();
        scanRecursive(new File(dirPath), records);
        return records;
    }

    private static void scanRecursive(File folder, ArrayList<FileRecord> records) throws Exception {
        File[] items = folder.listFiles();
        if (items == null) {
            return;
        }

        for (int i = 0; i < items.length; i++) {
            File item = items[i];
            if (item.isDirectory()) {
                scanRecursive(item, records);
            } else {
                long size = item.length();
                long lastModified = item.lastModified();
                String hash = calculateHash(item.toPath());
                records.add(new FileRecord(item.getAbsolutePath(), size, hash, lastModified));
            }
        }
    }

    private static void checkChanges(Map<String, FileRecord> oldIndex, List<FileRecord> newIndex) {
        if (oldIndex.isEmpty()) {
            System.out.println("Первое сканирование — сравнивать не с чем.");
            return;
        }

        HashMap<String, FileRecord> newMap = new HashMap<>();
        for (int i = 0; i < newIndex.size(); i++) {
            newMap.put(newIndex.get(i).path, newIndex.get(i));
        }

        int changed = 0;
        int deleted = 0;

        for (String path : oldIndex.keySet()) {
            FileRecord oldRec = oldIndex.get(path);
            if (!newMap.containsKey(path)) {
                System.out.println("Удалён: " + path);
                deleted++;
            } else if (!newMap.get(path).hash.equals(oldRec.hash)) {
                System.out.println("Изменён: " + path);
                changed++;
            }
        }

        if (changed == 0 && deleted == 0) {
            System.out.println("Изменений нет.");
        }
    }

    private static String calculateHash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(path.toFile());
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        in.close();

        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hashBytes.length; i++) {
            sb.append(String.format("%02x", hashBytes[i]));
        }
        return sb.toString();
    }

    private static Map<String, FileRecord> loadIndex(File file) {
        HashMap<String, FileRecord> map = new HashMap<>();
        List<FileRecord> list = loadIndexList(file);
        for (int i = 0; i < list.size(); i++) {
            FileRecord rec = list.get(i);
            map.put(rec.path, rec);
        }
        return map;
    }

    private static List<FileRecord> loadIndexList(File file) {
        ArrayList<FileRecord> list = new ArrayList<>();
        if (!file.exists()) {
            return list;
        }

        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|\\|\\|");
                if (parts.length == 4) {
                    list.add(new FileRecord(
                            parts[0],
                            Long.parseLong(parts[1]),
                            parts[2],
                            Long.parseLong(parts[3])
                    ));
                }
            }
            br.close();
        } catch (Exception e) {
            System.out.println("Не удалось загрузить индекс.");
        }
        return list;
    }

    private static void saveIndex(File file, List<FileRecord> records) throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
        for (int i = 0; i < records.size(); i++) {
            FileRecord rec = records.get(i);
            bw.write(rec.path + "|||" + rec.size + "|||" + rec.hash + "|||" + rec.lastModified);
            bw.newLine();
        }
        bw.close();
    }
}
