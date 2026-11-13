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

1. Read the receipt from the photo: seven lines, printed total 1 284,50, paid by card.
2. `get_context`; `resolve("карта", kinds=[account])` → "Карта Сбер"; `resolve` the budget items you
   would pick (Продукты, Бытовая химия).
3. `get_receipt_hints(texts=[all seven line texts], merchant="Пятёрочка")` — say it answers that
   "Салфетки влажные" went to "Бытовая химия" twice before, and that "Пакет" was corrected from
   "Продукты" to "Прочее" (`oftenCorrectedFrom: "Продукты"`). Use those.
4. `propose_receipt(merchant="Пятёрочка", date=…, accountFromId=<Карта Сбер>, currencyId=…,
   total="1284.50", imagePath="/home/…/receipt.jpg", lines=[{text, amount, budgetItemId} × 7],
   clientRequestId=<uuid>)`. Tell the human: "Открыл окно проверки чека в Kalita — проверьте статьи и
   сохраните."
5. `get_receipt_proposal(proposalId, waitSeconds=120)` → `saved`, 6 operations after merging; lines:
   six `confirmed`, one `corrected` ("Кофе в зёрнах": "Продукты" → "Кафе"). Reply: "Чек сохранён.
   Вы переложили «Кофе в зёрнах» в «Кафе» — учту."
   If it is `rejected` with a reason, nothing was written: say so and ask what to change.

## Handling an ambiguous name

> User: "Добавь перевод 5000 на вклад"

`resolve("вклад", kinds=[account])` comes back with two candidates — "Вклад Сбер" (0.6) and "Вклад
Тинькофф" (0.6) — neither a clear `1.0` winner. Do not guess. Ask: "Уточните, на какой счёт: «Вклад
Сбер» или «Вклад Тинькофф»?" — then resolve again (or just use the id the human picked) before
building the draft.
