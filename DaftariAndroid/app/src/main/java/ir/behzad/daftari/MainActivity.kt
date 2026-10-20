package ir.behzad.daftari

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val GREEN = 0xFF176B57.toInt()
private const val DARK = 0xFF123B35.toInt()
private const val PALE = 0xFFE3F4ED.toInt()
private const val RED = 0xFFB94351.toInt()

/** Local SQLite store. Version 2 adds customer records and migrates names from older entries. */
class LedgerDb(ctx: android.content.Context) : SQLiteOpenHelper(ctx, "daftari.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entries(
          id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, kind TEXT NOT NULL,
          item TEXT NOT NULL, qty REAL NOT NULL, unit_price REAL NOT NULL, amount REAL NOT NULL,
          person TEXT NOT NULL, note TEXT NOT NULL, date TEXT NOT NULL)""")
        createCustomersTable(db)
    }

    private fun createCustomersTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS customers(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL COLLATE NOCASE UNIQUE,
          phone TEXT NOT NULL DEFAULT '')""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createCustomersTable(db)
            // Preserve existing contact names by importing them into the customer list.
            db.execSQL("""INSERT OR IGNORE INTO customers(name)
                SELECT DISTINCT TRIM(person) FROM entries WHERE TRIM(person) <> ''""")
        }
    }

    fun add(scope: String, kind: String, item: String, qty: Double, unit: Double,
            amount: Double, person: String, note: String, date: String) {
        val v = ContentValues().apply {
            put("scope", scope); put("kind", kind); put("item", item); put("qty", qty)
            put("unit_price", unit); put("amount", amount); put("person", person)
            put("note", note); put("date", date)
        }
        writableDatabase.insert("entries", null, v)
    }

    fun all(): List<Array<String>> {
        val out = mutableListOf<Array<String>>()
        readableDatabase.rawQuery(
            "SELECT id,scope,kind,item,qty,unit_price,amount,person,note,date FROM entries ORDER BY id DESC", null
        ).use { c -> while (c.moveToNext()) out.add(Array(10) { i -> c.getString(i) ?: "" }) }
        return out
    }

    fun delete(id: String) { writableDatabase.delete("entries", "id=?", arrayOf(id)) }

    fun addCustomer(name: String, phone: String): Boolean {
        val values = ContentValues().apply { put("name", name.trim()); put("phone", phone.trim()) }
        return writableDatabase.insertWithOnConflict("customers", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun customers(): List<Array<String>> {
        val out = mutableListOf<Array<String>>()
        readableDatabase.rawQuery("SELECT id,name,phone FROM customers ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out.add(Array(3) { i -> c.getString(i) ?: "" })
        }
        return out
    }

    fun totals(scope: String): Triple<Double, Double, Double> {
        var income = 0.0; var expense = 0.0; var debt = 0.0
        readableDatabase.rawQuery("SELECT kind, SUM(amount) FROM entries WHERE scope=? GROUP BY kind", arrayOf(scope)).use { c ->
            while (c.moveToNext()) when (c.getString(0)) {
                "income" -> income = c.getDouble(1)
                "expense" -> expense = c.getDouble(1)
                "receivable", "payable", "customer_payment" -> debt += if (c.getString(0) == "customer_payment") -c.getDouble(1) else c.getDouble(1)
            }
        }
        return Triple(income, expense, debt)
    }
}

class MainActivity : Activity() {
    private lateinit var db: LedgerDb
    private lateinit var content: LinearLayout
    private lateinit var scopeSelect: Spinner
    private lateinit var kindSelect: Spinner
    private lateinit var itemInput: EditText
    private lateinit var qtyInput: EditText
    private lateinit var unitInput: EditText
    private lateinit var sellerInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var customerSelect: Spinner
    private lateinit var customerRow: LinearLayout
    private lateinit var sellerRow: LinearLayout
    private lateinit var totals: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var customerListBox: LinearLayout
    private lateinit var historyFilter: Spinner
    private var selectedScope = "shop"
    private val voiceRequest = 51

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = LedgerDb(this)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 30)
            setBackgroundColor(0xFFF3F7F5.toInt())
        }
        scroll.addView(content)
        setContentView(scroll)
        buildUi()
        refreshCustomers()
        refresh()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; setTextColor(DARK); textSize = 13f; setPadding(0, 12, 0, 5)
    }

    private fun edit(hint: String): EditText = EditText(this).apply {
        this.hint = hint; textSize = 15f; setSingleLine(true); setPadding(14, 8, 14, 8)
        setBackgroundColor(Color.WHITE)
    }

    private fun button(text: String, color: Int = GREEN, action: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
        setOnClickListener { action() }
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title; textSize = 18f; setTextColor(DARK); setTypeface(null, 1); setPadding(0, 18, 0, 8)
        })
    }

    private fun buildUi() {
        content.addView(TextView(this).apply {
            text = "📒 دفتر حساب"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(null, 1)
            setPadding(18, 24, 18, 8); setBackgroundColor(DARK)
        })
        content.addView(TextView(this).apply {
            text = "برنامه مستقل اندروید · ذخیره محلی روی گوشی"; textSize = 12f; setTextColor(Color.WHITE)
            setPadding(18, 0, 18, 20); setBackgroundColor(DARK)
        })

        section("خلاصه حساب مغازه و خانه")
        totals = TextView(this).apply {
            textSize = 14f; setTextColor(DARK); setPadding(14, 14, 14, 14); setBackgroundColor(PALE)
        }
        content.addView(totals)

        section("ثبت خرید، فروش یا حساب")
        content.addView(label("دفتر"))
        scopeSelect = Spinner(this)
        scopeSelect.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("مغازه", "شخصی / خانه"))
        scopeSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedScope = if (position == 0) "shop" else "personal"
                refresh()
            }
        }
        content.addView(scopeSelect)

        content.addView(label("نوع ثبت"))
        kindSelect = Spinner(this)
        kindSelect.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("خرید کالا / هزینه", "فروش / درآمد", "نسیه / افزایش بدهی مشتری", "بدهی به فروشنده", "دریافت وجه از مشتری / کاهش بدهی"))
        kindSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updatePersonFields() }
        }
        content.addView(kindSelect)

        content.addView(label("نام جنس یا عنوان حساب"))
        itemInput = edit("مثلاً شیر کم‌چرب یا روغن ۱.۵ لیتری"); content.addView(itemInput)
        content.addView(label("تعداد / مقدار"))
        qtyInput = edit("مثلاً 10"); content.addView(qtyInput)
        content.addView(label("قیمت هر واحد (تومان)"))
        unitInput = edit("مثلاً 50000"); content.addView(unitInput)

        // Purchases/payables use a supplier name; sales/receivables use the saved customer selector.
        sellerRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sellerRow.addView(label("نام فروشنده (اختیاری)"))
        sellerInput = edit("نام فروشنده"); sellerRow.addView(sellerInput)
        content.addView(sellerRow)

        customerRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        customerRow.addView(label("مشتری"))
        customerSelect = Spinner(this)
        customerSelect.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("— بدون انتخاب مشتری —"))
        customerRow.addView(customerSelect)
        customerRow.addView(button("＋ افزودن مشتری", DARK) { showAddCustomerDialog() })
        content.addView(customerRow)
        updatePersonFields()

        content.addView(label("یادداشت (اختیاری)"))
        noteInput = edit("توضیحات تکمیلی")
        content.addView(noteInput)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(button("🎙️ ثبت با گفتار", DARK) { startVoice() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("ذخیره حساب") { saveEntry() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)

        section("سوابق خرید و فروش")
        content.addView(label("نمایش سوابق"))
        historyFilter = Spinner(this)
        historyFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("همه ثبت‌ها", "فقط خریدها و هزینه‌ها", "فقط فروش‌ها و درآمدها", "فقط طلب‌ها", "فقط بدهی‌ها"))
        historyFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { refreshHistory() }
        }
        content.addView(historyFilter)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)

        section("مدیریت مشتریان")
        content.addView(button("＋ افزودن مشتری جدید") { showAddCustomerDialog() })
        customerListBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(customerListBox)

        content.addView(button("تازه‌سازی", DARK) { refresh(); refreshCustomers() })
        content.addView(TextView(this).apply {
            text = "اطلاعات در پایگاه داده همین گوشی ذخیره می‌شود. پیش از حذف برنامه یا نصب نسخه جدید، از اطلاعات مهم پشتیبان بگیرید. تشخیص گفتار اندروید ممکن است به اینترنت نیاز داشته باشد."
            textSize = 11f; setTextColor(0xFF6D817B.toInt()); setPadding(0, 20, 0, 0)
        })
    }

    private fun updatePersonFields() {
        if (!::kindSelect.isInitialized || !::sellerRow.isInitialized || !::customerRow.isInitialized) return
        val isCustomerEntry = kindSelect.selectedItemPosition == 1 || kindSelect.selectedItemPosition == 2 || kindSelect.selectedItemPosition == 4
        customerRow.visibility = if (isCustomerEntry) View.VISIBLE else View.GONE
        sellerRow.visibility = if (isCustomerEntry) View.GONE else View.VISIBLE
    }

    private fun showAddCustomerDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val nameInput = edit("نام مشتری (ضروری)")
        val phoneInput = edit("شماره تماس (اختیاری)")
        form.addView(label("نام مشتری")); form.addView(nameInput)
        form.addView(label("شماره تماس")); form.addView(phoneInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle("افزودن مشتری")
            .setView(form)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val phone = phoneInput.text.toString().trim()
                if (name.isBlank()) {
                    nameInput.error = "نام مشتری را وارد کن"
                    return@setOnClickListener
                }
                if (!db.addCustomer(name, phone)) {
                    Toast.makeText(this, "مشتری با این نام از قبل ثبت شده است.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                refreshCustomers(name)
                Toast.makeText(this, "مشتری ذخیره شد.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun refreshCustomers(selectName: String? = null) {
        if (!::db.isInitialized) return
        val customers = db.customers()
        if (::customerSelect.isInitialized) {
            val labels = listOf("— بدون انتخاب مشتری —") + customers.map { it[1] }
            customerSelect.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            if (!selectName.isNullOrBlank()) {
                val pos = labels.indexOf(selectName)
                if (pos >= 0) customerSelect.setSelection(pos)
            }
        }
        if (::customerListBox.isInitialized) {
            customerListBox.removeAllViews()
            if (customers.isEmpty()) {
                customerListBox.addView(TextView(this).apply {
                    text = "هنوز مشتری‌ای ثبت نشده است."; textSize = 13f; setTextColor(0xFF6D817B.toInt()); setPadding(0, 8, 0, 8)
                })
            } else {
                customers.forEach { c ->
                    customerListBox.addView(TextView(this).apply {
                        text = if (c[2].isBlank()) "👤 ${c[1]}  ›  مشاهده پرونده" else "👤 ${c[1]}  ·  ${c[2]}  ›  مشاهده پرونده"
                        textSize = 14f; setTextColor(DARK); setPadding(12, 16, 12, 16); setBackgroundColor(Color.WHITE)
                        setOnClickListener { showCustomerProfile(c[0], c[1], c[2]) }
                    })
                    customerListBox.addView(View(this), LinearLayout.LayoutParams(1, 5))
                }
            }
        }
    }

    private fun showCustomerProfile(customerId: String, name: String, phone: String) {
        val records = db.all().filter { it[1] == selectedScope && it[7].equals(name, ignoreCase = true) }
        val balance = records.sumOf { r ->
            val amount = r[6].toDoubleOrNull() ?: 0.0
            when (r[2]) { "receivable" -> amount; "customer_payment" -> -amount; else -> 0.0 }
        }
        val details = buildString {
            append("نام: $name\n")
            append("تلفن: ${phone.ifBlank { "ثبت نشده" }}\n")
            append("مانده بدهی مشتری: ${balance.toLong()} تومان\n\n")
            if (records.isEmpty()) append("هنوز تراکنشی ثبت نشده است.") else records.take(30).forEach { r ->
                val kind = when (r[2]) { "receivable" -> "نسیه / افزایش بدهی"; "customer_payment" -> "پرداخت / کاهش بدهی"; "income" -> "فروش"; else -> r[2] }
                append("• $kind: ${r[6]} تومان — ${r[9]}\n")
                if (r[8].isNotBlank()) append("  ${r[8]}\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("پرونده مشتری: $name")
            .setMessage(details)
            .setPositiveButton("ثبت نسیه") { _, _ -> quickCustomerTransaction(name, false) }
            .setNeutralButton("دریافت وجه") { _, _ -> quickCustomerTransaction(name, true) }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun quickCustomerTransaction(name: String, isPayment: Boolean) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val amountInput = edit("مبلغ به تومان")
        val note = edit("توضیح (اختیاری)")
        form.addView(label("مبلغ")); form.addView(amountInput)
        form.addView(label("توضیح")); form.addView(note)
        val dialog = AlertDialog.Builder(this).setTitle(if (isPayment) "دریافت وجه از $name" else "ثبت نسیه برای $name")
            .setView(form).setNegativeButton("انصراف", null).setPositiveButton("ذخیره", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = normalizeDigits(amountInput.text.toString()).replace(",", "").trim().toDoubleOrNull()
                if (amount == null || amount <= 0) { amountInput.error = "مبلغ معتبر وارد کن"; return@setOnClickListener }
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                db.add(selectedScope, if (isPayment) "customer_payment" else "receivable",
                    if (isPayment) "دریافت وجه" else "نسیه", 1.0, amount, amount, name,
                    note.text.toString().trim(), date)
                dialog.dismiss(); refresh(); refreshCustomers()
                Toast.makeText(this, "تراکنش ذخیره شد.", Toast.LENGTH_SHORT).show()
                showCustomerProfile("", name, db.customers().firstOrNull { it[1].equals(name, true) }?.get(2) ?: "")
            }
        }
        dialog.show()
    }

    private fun normalizeDigits(value: String): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        return value.map { ch ->
            when {
                ch in persian -> ('0'.code + persian.indexOf(ch)).toChar()
                ch in arabic -> ('0'.code + arabic.indexOf(ch)).toChar()
                ch == '٬' || ch == '،' -> ','
                else -> ch
            }
        }.joinToString("")
    }

    private fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "مثلاً: ده بسته شیر هر کدام پنجاه هزار تومان خریدم")
            startActivityForResult(i, voiceRequest)
        } catch (e: Exception) {
            Toast.makeText(this, "سرویس تشخیص گفتار در گوشی در دسترس نیست.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API but widely supported")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == voiceRequest && resultCode == RESULT_OK) {
            val raw = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            val said = normalizeDigits(raw)
            val amountMatch = Regex("""(\d[\d,]*)\s*(میلیون|هزار)?\s*(تومان|تومن)""").find(said)
            val qtyMatch = Regex("""(\d[\d,]*)\s*(?:بسته|عدد|کیلو|کیلوگرم|قلم|تا|عدد)""").find(said)
            amountMatch?.let {
                val n = it.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                val factor = when (it.groupValues[2]) { "میلیون" -> 1000000.0; "هزار" -> 1000.0; else -> 1.0 }
                unitInput.setText((n * factor).toLong().toString())
            }
            qtyMatch?.let { qtyInput.setText(it.groupValues[1].replace(",", "")) }
            var item = said
            amountMatch?.let { item = item.replace(it.value, " ") }
            qtyMatch?.let { item = item.replace(it.value, " ") }
            item = item.replace(Regex("""\b(خریدم|خریدیم|خرید|فروختم|فروش|پرداختم|دادم|گرفتم)\b"""), " ")
                .replace(Regex("""\s+"""), " ").trim(' ', '،', '.', '؛')
            if (item.isNotBlank()) itemInput.setText(item)
            noteInput.setText("گفتار: $raw")
            AlertDialog.Builder(this).setTitle("بررسی گفتار")
                .setMessage("متن تشخیص‌داده‌شده:\n$raw\n\nاطلاعات جداشده را بررسی کن؛ تشخیص خودکار ممکن است اشتباه باشد. سپس ذخیره را بزن.")
                .setPositiveButton("متوجه شدم", null).show()
        }
    }

    private fun saveEntry() {
        val item = itemInput.text.toString().trim()
        val qty = normalizeDigits(qtyInput.text.toString()).replace(",", "").toDoubleOrNull() ?: 1.0
        val unit = normalizeDigits(unitInput.text.toString()).replace(",", "").toDoubleOrNull() ?: 0.0
        if (item.isBlank()) {
            Toast.makeText(this, "نام جنس یا عنوان را وارد کن.", Toast.LENGTH_SHORT).show(); return
        }
        if (qty <= 0 || unit < 0) {
            Toast.makeText(this, "تعداد یا قیمت معتبر نیست.", Toast.LENGTH_SHORT).show(); return
        }
        val position = kindSelect.selectedItemPosition
        val kind = when (position) { 0 -> "expense"; 1 -> "income"; 2 -> "receivable"; 3 -> "payable"; else -> "customer_payment" }
        val person = if (position == 1 || position == 2 || position == 4) {
            customerSelect.selectedItem?.toString()?.takeUnless { it.startsWith("—") } ?: ""
        } else sellerInput.text.toString().trim()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        db.add(selectedScope, kind, item, qty, unit, qty * unit, person,
            noteInput.text.toString().trim(), date)
        itemInput.setText(""); qtyInput.setText(""); unitInput.setText(""); sellerInput.setText(""); noteInput.setText("")
        if (::customerSelect.isInitialized && customerSelect.adapter != null) customerSelect.setSelection(0)
        Toast.makeText(this, "حساب ذخیره شد.", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun refresh() {
        if (!::db.isInitialized) return
        val (inc, exp, deb) = db.totals(selectedScope)
        totals.text = "${if (selectedScope == "shop") "مغازه" else "شخصی / خانه"}\nدرآمد: ${inc.toLong()} تومان   هزینه: ${exp.toLong()} تومان\nمانده: ${(inc - exp).toLong()} تومان   طلب/بدهی: ${deb.toLong()} تومان"
        refreshHistory()
    }

    private fun refreshHistory() {
        if (!::db.isInitialized || !::listBox.isInitialized) return
        listBox.removeAllViews()
        val filter = if (::historyFilter.isInitialized) historyFilter.selectedItemPosition else 0
        val records = db.all().filter { r ->
            if (r[1] != selectedScope) return@filter false
            when (filter) {
                1 -> r[2] == "expense"
                2 -> r[2] == "income"
                3 -> r[2] == "receivable"
                4 -> r[2] == "payable"
                else -> true
            }
        }
        if (records.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "برای این بخش هنوز موردی ثبت نشده است."; setTextColor(0xFF6D817B.toInt()); setPadding(0, 12, 0, 12)
            })
            return
        }
        records.take(100).forEach { r ->
            val kindName = when (r[2]) {
                "income" -> "فروش / درآمد"
                "expense" -> "خرید / هزینه"
                "receivable" -> "نسیه / افزایش بدهی مشتری"
                "customer_payment" -> "دریافت وجه از مشتری"
                else -> "بدهی به فروشنده"
            }
            listBox.addView(TextView(this).apply {
                val personLine = if (r[7].isBlank()) "" else "\nطرف حساب: ${r[7]}"
                val noteLine = if (r[8].isBlank()) "" else "\nیادداشت: ${r[8]}"
                text = "$kindName · ${r[3]}\nتعداد: ${r[4]} × ${r[5]} تومان = ${r[6]} تومان$personLine$noteLine\nتاریخ: ${r[9]}"
                textSize = 13f; setTextColor(DARK); setPadding(12, 12, 12, 12); setBackgroundColor(Color.WHITE)
            })
            listBox.addView(button("حذف این ثبت", RED) {
                AlertDialog.Builder(this).setMessage("این ثبت حذف شود?")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف") { _, _ -> db.delete(r[0]); refresh() }.show()
            })
            listBox.addView(View(this), LinearLayout.LayoutParams(1, 8))
        }
    }
}
