package ir.behzad.daftari2

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
import org.json.JSONArray
import org.json.JSONObject

private const val GREEN = 0xFF176B57.toInt()
private const val DARK = 0xFF123B35.toInt()
private const val PALE = 0xFFE3F4ED.toInt()

class LedgerDb(ctx: android.content.Context) : SQLiteOpenHelper(ctx, "daftari_new.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entries(
          id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, kind TEXT NOT NULL,
          item TEXT NOT NULL, qty REAL NOT NULL, unit_price REAL NOT NULL, amount REAL NOT NULL,
          person TEXT NOT NULL, note TEXT NOT NULL, date TEXT NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun add(scope:String,kind:String,item:String,qty:Double,unit:Double,amount:Double,person:String,note:String,date:String) {
        val v=ContentValues().apply {
            put("scope",scope);put("kind",kind);put("item",item);put("qty",qty);put("unit_price",unit)
            put("amount",amount);put("person",person);put("note",note);put("date",date)
        }
        writableDatabase.insert("entries",null,v)
    }
    fun all(): List<Array<String>> {
        val out= mutableListOf<Array<String>>()
        readableDatabase.rawQuery("SELECT id,scope,kind,item,qty,unit_price,amount,person,note,date FROM entries ORDER BY id DESC",null).use { c ->
            while(c.moveToNext()) out.add(Array(10){i->c.getString(i) ?: ""})
        }
        return out
    }
    fun delete(id:String){writableDatabase.delete("entries","id=?", arrayOf(id))}
    fun totals(scope:String): Triple<Double,Double,Double> {
        var income=0.0;var expense=0.0;var debt=0.0
        readableDatabase.rawQuery("SELECT kind, SUM(amount) FROM entries WHERE scope=? GROUP BY kind",arrayOf(scope)).use { c ->
            while(c.moveToNext()) when(c.getString(0)){
                "income" -> income=c.getDouble(1)
                "expense" -> expense=c.getDouble(1)
                "receivable","payable" -> debt+=c.getDouble(1)
            }
        }
        return Triple(income,expense,debt)
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
    private lateinit var personInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var totals: TextView
    private lateinit var listBox: LinearLayout
    private var selectedScope="shop"
    private val voiceRequest=51
    private val backupExportRequest=71
    private val backupImportRequest=72

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db=LedgerDb(this)
        val scroll=ScrollView(this)
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,20,24,30);setBackgroundColor(0xFFF3F7F5.toInt())}
        scroll.addView(content)
        setContentView(scroll)
        buildUi()
        refresh()
    }
    private fun label(text:String)=TextView(this).apply{this.text=text;setTextColor(DARK);textSize=13f;setPadding(0,12,0,5)}
    private fun edit(hint:String):EditText=EditText(this).apply{
        this.hint=hint;textSize=15f;setSingleLine(true);setPadding(14,8,14,8)
        setBackgroundColor(Color.WHITE)
    }
    private fun button(text:String, color:Int=GREEN, action:()->Unit)=Button(this).apply{
        this.text=text;setTextColor(Color.WHITE);setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
        setOnClickListener{action()}
    }
    private fun section(title:String) {
        content.addView(TextView(this).apply{ text=title;textSize=18f;setTextColor(DARK);setTypeface(null,1);setPadding(0,18,0,8) })
    }
    private fun buildUi(){
        content.addView(TextView(this).apply{
            text="📒 دفتر حساب";textSize=26f;setTextColor(Color.WHITE);setTypeface(null,1)
            setPadding(18,24,18,8);setBackgroundColor(DARK)
        })
        content.addView(TextView(this).apply{
            text="برنامه مستقل اندروید · ذخیره محلی روی گوشی";textSize=12f;setTextColor(Color.WHITE)
            setPadding(18,0,18,20);setBackgroundColor(DARK)
        })
        section("خلاصه حساب مغازه و خانه")
        totals=TextView(this).apply{ textSize=14f;setTextColor(DARK);setPadding(14,14,14,14);setBackgroundColor(PALE) }
        content.addView(totals)
        section("ثبت خرید، فروش یا حساب")
        content.addView(label("دفتر"))
        scopeSelect=Spinner(this)
        scopeSelect.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("مغازه","شخصی / خانه"))
        scopeSelect.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){selectedScope=if(position==0)"shop" else "personal";refresh()}
        }
        content.addView(scopeSelect)
        content.addView(label("نوع ثبت"))
        kindSelect=Spinner(this)
        kindSelect.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("خرید کالا / هزینه","فروش / درآمد","طلب از مشتری","بدهی به فروشنده"))
        content.addView(kindSelect)
        content.addView(label("نام جنس یا عنوان حساب"))
        itemInput=edit("مثلاً شیر کم‌چرب یا روغن ۱.۵ لیتری");content.addView(itemInput)
        content.addView(label("تعداد / مقدار"))
        qtyInput=edit("مثلاً 10");content.addView(qtyInput)
        content.addView(label("قیمت هر واحد (تومان)"))
        unitInput=edit("مثلاً 50000");content.addView(unitInput)
        content.addView(label("نام مشتری / فروشنده (اختیاری)"))
        personInput=edit("نام شخص");content.addView(personInput)
        content.addView(label("یادداشت (اختیاری)"))
        noteInput=edit("توضیحات");content.addView(noteInput)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        row.addView(button("🎙️ ثبت با گفتار",DARK){startVoice()},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row.addView(button("ذخیره حساب"){saveEntry()},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        content.addView(row)
        section("سوابق")
        listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        content.addView(listBox)
        content.addView(button("تحديث / تازه‌سازی",DARK){refresh()})
        content.addView(button("ذخیرهٔ پشتیبان در فایل",DARK){exportBackup()})
        content.addView(button("بازیابی از فایل پشتیبان",DARK){importBackup()})
        content.addView(TextView(this).apply{
            text="توجه: اطلاعات در پایگاه داده همین گوشی ذخیره می‌شود. برای حذف برنامه یا خرابی گوشی، پشتیبان جداگانه لازم است. تشخیص گفتار اندروید ممکن است به اینترنت نیاز داشته باشد."
            textSize=11f;setTextColor(0xFF6D817B.toInt());setPadding(0,20,0,0)
        })
    }
    private fun exportBackup(){
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "daftari-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json")
        }
        try { startActivityForResult(i, backupExportRequest) }
        catch (e: Exception) { Toast.makeText(this,"انتخاب محل ذخیره در دسترس نیست.",Toast.LENGTH_LONG).show() }
    }
    private fun importBackup(){
        AlertDialog.Builder(this).setTitle("بازیابی پشتیبان")
            .setMessage("ثبت‌های فایل به اطلاعات فعلی اضافه می‌شوند و جایگزین آن‌ها نمی‌شوند. اگر فایل را دوبار وارد کنی، ممکن است ثبت‌ها تکراری شوند.")
            .setNegativeButton("لغو",null).setPositiveButton("انتخاب فایل") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                try { startActivityForResult(i, backupImportRequest) }
                catch (e: Exception) { Toast.makeText(this,"انتخاب فایل در دسترس نیست.",Toast.LENGTH_LONG).show() }
            }.show()
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

    private fun startVoice(){
        try{
            val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"fa-IR")
            i.putExtra(RecognizerIntent.EXTRA_PROMPT,"مثلاً: ده بسته شیر هر کدام پنجاه هزار تومان خریدم")
            startActivityForResult(i,voiceRequest)
        }catch(e:Exception){Toast.makeText(this,"سرویس تشخیص گفتار در گوشی در دسترس نیست.",Toast.LENGTH_LONG).show()}
    }
    @Deprecated("Deprecated in Android API but widely supported")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode == RESULT_OK && data?.data != null && requestCode == backupExportRequest){
            try {
                val records = JSONArray()
                db.all().forEach { r ->
                    records.put(JSONObject().put("scope",r[1]).put("kind",r[2]).put("item",r[3])
                        .put("qty",r[4]).put("unit_price",r[5]).put("amount",r[6])
                        .put("person",r[7]).put("note",r[8]).put("date",r[9]))
                }
                val root = JSONObject().put("format","daftari-backup-v1").put("records",records)
                contentResolver.openOutputStream(data.data!!)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(root.toString(2)) }
                Toast.makeText(this,"پشتیبان در فایل ذخیره شد.",Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this,"ذخیرهٔ پشتیبان ناموفق بود: ${e.message}",Toast.LENGTH_LONG).show()
            }
            return
        }
        if(resultCode == RESULT_OK && data?.data != null && requestCode == backupImportRequest){
            try {
                val text = contentResolver.openInputStream(data.data!!)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("فایل خوانده نشد")
                val root = JSONObject(text)
                if(root.optString("format") != "daftari-backup-v1") throw IllegalArgumentException("این فایل پشتیبان دفتر حساب نیست")
                val records = root.getJSONArray("records")
                var restored = 0
                val allowed = setOf("shop", "personal")
                for(i in 0 until records.length()) {
                    val r = records.getJSONObject(i)
                    val scope = r.optString("scope", "shop")
                    val kind = r.optString("kind", "expense")
                    if(scope !in allowed || kind !in setOf("income","expense","receivable","payable")) continue
                    db.add(scope,kind,r.optString("item","ثبت واردشده"),r.optString("qty","1").toDoubleOrNull() ?: 1.0,
                        r.optString("unit_price","0").toDoubleOrNull() ?: 0.0,
                        r.optString("amount","0").toDoubleOrNull() ?: 0.0,
                        r.optString("person",""),r.optString("note",""),r.optString("date",""))
                    restored++
                }
                refresh()
                AlertDialog.Builder(this).setTitle("بازیابی انجام شد")
                    .setMessage("$restored ثبت اضافه شد. اگر همین فایل را دوباره وارد کنی، ثبت‌ها تکراری می‌شوند.")
                    .setPositiveButton("باشه",null).show()
            } catch (e: Exception) {
                Toast.makeText(this,"بازیابی ناموفق بود: ${e.message}",Toast.LENGTH_LONG).show()
            }
            return
        }
        if(requestCode==voiceRequest && resultCode==RESULT_OK){
            val raw=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            val said = normalizeDigits(raw)
            // Accept both Persian and Latin digits; parse the amount separately from the quantity.
            val amountMatch = Regex("""(\d[\d,]*)\s*(میلیون|هزار)?\s*(تومان|تومن)""").find(said)
            val qtyMatch = Regex("""(\d[\d,]*)\s*(?:بسته|عدد|کیلو|کیلوگرم|قلم|تا|عدد)""").find(said)
            amountMatch?.let {
                val n = it.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                val factor = when(it.groupValues[2]) { "میلیون" -> 1000000.0; "هزار" -> 1000.0; else -> 1.0 }
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
                .setPositiveButton("متوجه شدم",null).show()
        }
    }
    private fun saveEntry(){
        val item=itemInput.text.toString().trim()
        val qty=qtyInput.text.toString().trim().replace(",","").toDoubleOrNull() ?: 1.0
        val unit=unitInput.text.toString().trim().replace(",","").toDoubleOrNull() ?: 0.0
        if(item.isBlank()){Toast.makeText(this,"نام جنس یا عنوان را وارد کن.",Toast.LENGTH_SHORT).show();return}
        if(qty<=0||unit<0){Toast.makeText(this,"تعداد یا قیمت معتبر نیست.",Toast.LENGTH_SHORT).show();return}
        val k=when(kindSelect.selectedItemPosition){0->"expense";1->"income";2->"receivable";else->"payable"}
        val date=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
        db.add(selectedScope,k,item,qty,unit,qty*unit,personInput.text.toString().trim(),noteInput.text.toString().trim(),date)
        itemInput.setText("");qtyInput.setText("");unitInput.setText("");personInput.setText("");noteInput.setText("")
        Toast.makeText(this,"حساب ذخیره شد.",Toast.LENGTH_SHORT).show();refresh()
    }
    private fun refresh(){
        if(!::db.isInitialized)return
        val (inc,exp,deb)=db.totals(selectedScope)
        totals.text="${if(selectedScope=="shop")"مغازه" else "شخصی / خانه"}\nدرآمد: ${inc.toLong()} تومان   هزینه: ${exp.toLong()} تومان\nمانده: ${(inc-exp).toLong()} تومان   طلب/بدهی: ${deb.toLong()} تومان"
        if(!::listBox.isInitialized)return
        listBox.removeAllViews()
        val records=db.all().filter{it[1]==selectedScope}
        if(records.isEmpty()){listBox.addView(TextView(this).apply{text="هنوز موردی ثبت نشده.";setTextColor(0xFF6D817B.toInt());setPadding(0,12,0,12)});return}
        records.take(100).forEach { r ->
            val kindName=when(r[2]){"income"->"فروش/درآمد";"expense"->"خرید/هزینه";"receivable"->"طلب";else->"بدهی"}
            val tv=TextView(this).apply{
                text="$kindName · ${r[3]}\nتعداد: ${r[4]} × ${r[5]} تومان = ${r[6]} تومان\n${r[7]}  ${r[8]}\n${r[9]}"
                textSize=13f;setTextColor(DARK);setPadding(12,12,12,12);setBackgroundColor(Color.WHITE)
            }
            listBox.addView(tv)
            listBox.addView(button("حذف این ثبت",0xFFB94351.toInt()){AlertDialog.Builder(this).setMessage("این ثبت حذف شود؟").setNegativeButton("نه",null).setPositiveButton("حذف"){_,_->db.delete(r[0]);refresh()}.show()})
            val gap=View(this);listBox.addView(gap,LinearLayout.LayoutParams(1,8))
        }
    }
}