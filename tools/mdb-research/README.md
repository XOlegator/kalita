<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# Исследовательские скрипты (октябрь 2026)

Одноразовые скрипты, которыми изучался формат `Data.mdb` X-Cash и проверялась запись через Jackcess.
Это не продуктовый код. В скриптах захардкожены пути временного каталога, их нужно поправить под себя.
Результаты описаны в `../../../dev_plan.md`, раздел 8.

- `python/` — разбор Jet4 без зависимостей: заголовок (`hdr.py`), usage maps (`umap.py`), DFM-формы из exe (`dfm.py`),
  схема и данные через `access_parser` (`schema.py`, `data.py`; учтите, что `access_parser` дублирует строки со «висящих» страниц).
- `jackcess/` — `Dump.java` (схема и строки), `WriteTest.java` (стресс-запись: 600 операций, 400 курсов, правки, удаления),
  `Export.java` (нормализованная выгрузка для сравнения), `Var.java` (варианты для проверки в X-Cash).
  Запуск: `java -cp "jars/*" WriteTest.java copy.mdb`. Для форка spannm поменяйте пакет на `io.github.spannm.jackcess`.
- `jet-oracle/` — проверка настоящим Microsoft Jet 4.0 (DAO 3.6) под Wine: `verify.vbs` (выгрузка и обход всех индексов),
  `jetops.vbs` (запись средствами Jet), `compact.vbs` (`DBEngine.CompactDatabase`), `pipeline.sh` (полный цикл
  Jackcess → Jet → Jackcess → Compact), `xshot.sh` (запуск оригинального X-Cash на базе и скриншот).
  Нужен Wine-префикс с установленными Jet 4.0 и DAO 3.6, 32-битный `cscript.exe` и каталог `users` с TEMP.
- `qtjambi-poc/Hello.java` — окно Qt Widgets (QtJambi) с таблицей операций, прочитанных Jackcess, и `QDockWidget`.
- `qj.png` — скриншот прототипа QtJambi (`qtjambi-poc/Hello.java`). Скриншоты самого X-Cash, которые `jet-oracle/xshot.sh` делает для сверки, в репозиторий не коммитятся (13.1/13.2 — "в репозитории нет ... скриншотов оригинала").
