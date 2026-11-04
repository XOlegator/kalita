<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# Worked examples

## Logging a day's spending

> User: "Внеси: кофе 350 с карты, вчера такси 600"

1. `get_context` — today's date, base currency, dictionaries.
2. `resolve("кофе", kinds=[budget_item])` → "Кафе" (0.93); `resolve("карта", kinds=[account])` →
   "Карта Сбер" (0.88); `resolve("такси", kinds=[budget_item])` → "Транспорт/Такси" (0.97).
   Each is a clear top candidate, so no need to ask — but if any of these had come back ambiguous or
   empty, that would be the moment to show the candidates and ask instead of guessing.
3. `add_operations(dryRun=true, drafts=[...])` with today's date for the coffee and yesterday's for
   the taxi, both as Расход (expense) with `accountFrom` set to the resolved account.
4. Show the preview: "Кофе — 350 ₽ (Кафе, Карта Сбер, сегодня); Такси — 600 ₽ (Транспорт/Такси,
   Карта Сбер, вчера). Записать?"
5. On "да": `add_operations` again, same `clientRequestId`, without `dryRun` (mode `draft` or
   `write` depending on the connection). On a `write` connection with an elicitation-capable client,
   the tool itself also asks to confirm before writing — that is in addition to, not instead of,
   this preview.

## Itemizing a receipt

> User: "Вот чек из Пятёрочки [фото]" (seven line items, total 1 284,50, paid by card)

1. Read the receipt (directly, from the photo/file, or via `recognize_receipt` once that tool
   exists — dev_plan.md 1.4).
2. Group the line items by budget item — e.g. five groceries items under "Продукты" (1 102,00) and
   two household-chemical items under "Бытовая химия" (182,50). Resolve each budget item
   independently; do not assume the whole receipt is one category.
3. `resolve("Пятёрочка", kinds=[counterparty])` for "Кто/Кому" (optional field, but worth filling in
   when the receipt names a specific store).
4. `add_operations(dryRun=true, drafts=[...])` — one draft per *budget item group*, not one per line
   item, unless the human wants that level of detail; same date, same `accountFrom` (the card),
   `comment` naming what each group covers.
5. Preview as a small table: "Продукты — 1 102,00 ₽; Бытовая химия — 182,50 ₽. Итого 1 284,50 ₽.
   Записать?"
6. On confirmation, write for real with the same `clientRequestId` — one call for the whole receipt,
   so the human reviews and confirms it once, not item by item.

## Handling an ambiguous name

> User: "Добавь перевод 5000 на вклад"

`resolve("вклад", kinds=[account])` comes back with two candidates — "Вклад Сбер" (0.6) and "Вклад
Тинькофф" (0.6) — neither a clear `1.0` winner. Do not guess. Ask: "Уточните, на какой счёт: «Вклад
Сбер» или «Вклад Тинькофф»?" — then resolve again (or just use the id the human picked) before
building the draft.
