# Folder Indexer Java

Консольное приложение на Java для индексации файлов, поиска дубликатов и проверки резервных копий.

## Особенности

- Сканирование файловой системы
- Поиск дубликатов
- Проверка целостности резервных копий
- Использование SQLite для хранения индекса

## Требования

- Java 11+
- Библиотека `sqlite-jdbc`

## Использование

```bash
javac -cp ".:sqlite-jdbc-*.jar" FileIndexer.java
java -cp ".:sqlite-jdbc-*.jar" FileIndexer index /path/to/directory
