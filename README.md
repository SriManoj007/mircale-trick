# My Cashbook

A personal tracker for every transaction and your monthly cash flow.

- Record money in, money out and transfers between accounts
- Monthly overview: income, spending, net cash flow, closing balance
- Six-month cash flow chart and spending by category, with optional budgets
- Amounts and dates in Indian format (₹, lakh grouping, day first)
- Import from Gmail bank alerts, pasted SMS, or a bank statement (Excel, CSV, PDF), with a review step
- Ask Claude: a built-in chat that answers questions about your money, adds or fixes entries, and explains the app
- Search and filter all entries; export CSV, back up and restore

It is a single file, `index.html`, with no build step. Open it in a browser and
entries are kept in that browser's storage. When published as a Claude artifact
it also keeps a private copy in your account so the book follows you across devices.

## Android app

`MyCashbook.apk` is the same cashbook as a phone app. With SMS permission it reads
bank and card messages from the inbox each time it opens and adds every debit or
credit as a transaction. Everything stays on the phone.

Source is in `android/` (a WebView around `index.html` plus a small SMS bridge).
Build with `gradle assembleRelease` inside `android/`, with the Android SDK installed.
