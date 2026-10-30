package com.vcdconv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.InputStream

class TrackInfo(val number: Int, val audio: Boolean, val fileIdx: Int) {
    var idx0: Long = -1
    var idx1: Long = -1
}

class MainActivity : Activity() {
    private lateinit var spinner: Spinner
    private lateinit var etId: EditText
    private lateinit var etName: EditText
    private lateinit var cbHeader: CheckBox
    private lateinit var cbVmc: CheckBox
    private lateinit var cbMulti: CheckBox
    private lateinit var cbShare: CheckBox
    private lateinit var btnGo: Button
    private lateinit var bar: ProgressBar
    private lateinit var tvLog: TextView
    private var tree: DocumentFile? = null
    private var cues: List<DocumentFile> = emptyList()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Conversor PS1 → VCD (POPStarter)"; textSize = 20f })
        root.addView(Button(this).apply {
            text = "1. Escolher pasta com .cue e .bin"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                startActivityForResult(i, 1)
            }
        })
        spinner = Spinner(this)
        root.addView(spinner)
        etId = EditText(this).apply { hint = "ID do jogo (vazio = detectar), ex: SLUS_007.76" }
        root.addView(etId)
        etName = EditText(this).apply { hint = "Nome do jogo (máx. 20 caracteres)" }
        root.addView(etName)
        cbHeader = CheckBox(this).apply { text = "Incluir cabeçalho POPS (necessário)"; isChecked = true }
        root.addView(cbHeader)
        cbVmc = CheckBox(this).apply { text = "Gerar memory card (pasta com SLOT0/SLOT1.VMC)"; isChecked = true }
        root.addView(cbVmc)
        cbMulti = CheckBox(this).apply { text = "Modo multidisco (gera DISCS.TXT, até 4 discos)" }
        root.addView(cbMulti)
        cbShare = CheckBox(this).apply { text = "Multidisco: compartilhar o mesmo memory card"; isChecked = true }
        root.addView(cbShare)
        btnGo = Button(this).apply { text = "2. Converter" }
        root.addView(btnGo)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)
        tvLog = TextView(this)
        root.addView(tvLog)
        setContentView(ScrollView(this).apply { addView(root) })

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos in cues.indices) etName.setText((cues[pos].name ?: "GAME").substringBeforeLast('.'))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnGo.setOnClickListener {
            if (tree == null || cues.isEmpty()) {
                log("Escolha uma pasta que tenha um arquivo .cue.")
                return@setOnClickListener
            }
            val idIn = etId.text.toString().trim()
            val nm = etName.text.toString()
            val h = cbHeader.isChecked
            val v = cbVmc.isChecked
            val share = cbShare.isChecked
            if (cbMulti.isChecked) {
                askDiscs { sel -> start(sel, idIn, nm, h, v, true, share) }
            } else {
                val i = spinner.selectedItemPosition
                if (i !in cues.indices) {
                    log("Escolha um arquivo .cue.")
                    return@setOnClickListener
                }
                start(listOf(cues[i]), idIn, nm, h, v, false, share)
            }
        }
    }

    private fun askDiscs(onOk: (List<DocumentFile>) -> Unit) {
        val names = cues.map { it.name ?: "?" }.toTypedArray()
        val checked = BooleanArray(names.size)
        AlertDialog.Builder(this)
            .setTitle("Marque os discos (ordem alfabética, máx. 4)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Converter") { _, _ -> onOk(cues.filterIndexed { i, _ -> checked[i] }) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun start(
        sel: List<DocumentFile>, idIn: String, nm: String,
        h: Boolean, v: Boolean, multi: Boolean, share: Boolean
    ) {
        btnGo.isEnabled = false
        bar.progress = 0
        tvLog.text = ""
        Thread {
            try {
                convertAll(sel, idIn, nm, h, v, multi, share)
                log("Concluído!")
            } catch (e: Throwable) {
                log("Erro: ${e.message}")
            }
            runOnUiThread { btnGo.isEnabled = true }
        }.start()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 1 || res != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        val t = DocumentFile.fromTreeUri(this, uri) ?: return
        tree = t
        cues = t.listFiles()
            .filter { it.isFile && (it.name ?: "").endsWith(".cue", true) }
            .sortedBy { it.name }
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, cues.map { it.name ?: "?" }
        )
        log(if (cues.isEmpty()) "Nenhum .cue encontrado nessa pasta." else "${cues.size} arquivo(s) .cue encontrado(s).")
    }

    private fun log(m: String) = runOnUiThread { tvLog.append(m + "\n") }

    private fun parseCue(text: String): Pair<List<String>, List<TrackInfo>> {
        val files = ArrayList<String>()
        val tracks = ArrayList<TrackInfo>()
        var cur: TrackInfo? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            val u = l.uppercase()
            val p = l.split(Regex("\\s+"))
            when {
                u.startsWith("FILE") -> {
                    val a = l.indexOf('"')
                    val b = if (a >= 0) l.indexOf('"', a + 1) else -1
                    files.add(if (a >= 0 && b > a) l.substring(a + 1, b) else p[1])
                }
                u.startsWith("TRACK") -> {
                    val mode = p[2].uppercase()
                    if (mode != "AUDIO" && !mode.endsWith("/2352"))
                        throw Exception("Modo de trilha não suportado: $mode (só setores de 2352 bytes)")
                    cur = TrackInfo(p[1].toInt(), mode == "AUDIO", files.size - 1)
                    tracks.add(cur)
                }
                u.startsWith("INDEX") -> {
                    val n = p[1].toInt()
                    if (cur != null && (n == 0 || n == 1)) {
                        val t = p[2].split(":").map { it.toLong() }
                        val v = (t[0] * 60 + t[1]) * 75 + t[2]
                        if (n == 1) cur.idx1 = v else cur.idx0 = v
                    }
                }
            }
        }
        if (files.isEmpty() || tracks.isEmpty()) throw Exception("CUE inválido (sem FILE/TRACK)")
        if (tracks.any { it.idx1 < 0 || it.fileIdx < 0 }) throw Exception("CUE inválido (falta INDEX 01)")
        return Pair(files, tracks)
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var t = 0
        while (t < buf.size) {
            val n = ins.read(buf, t, buf.size - t)
            if (n < 0) break
            t += n
        }
        return t
    }

    private fun detectId(f: DocumentFile): String? {
        val buf = ByteArray(8 * 1024 * 1024)
        val n = (contentResolver.openInputStream(f.uri) ?: return null).use { readFully(it, buf) }
        val s = String(buf, 0, n, Charsets.ISO_8859_1)
        val m = Regex("""cdrom0?:[\\/]*([A-Z]{4})_(\d{3})\.?(\d{2})""", RegexOption.IGNORE_CASE).find(s) ?: return null
        return "${m.groupValues[1].uppercase()}_${m.groupValues[2]}.${m.groupValues[3]}"
    }

    private fun bcd(v: Int): Byte = (((v / 10) shl 4) or (v % 10)).toByte()

    private fun putMsf(b: ByteArray, off: Int, lba: Long) {
        val f = lba + 150
        b[off] = bcd((f / 75 / 60).toInt())
        b[off + 1] = bcd(((f / 75) % 60).toInt())
        b[off + 2] = bcd((f % 75).toInt())
    }

    private fun putLe32(b: ByteArray, off: Int, v: Long) {
        for (k in 0..3) b[off + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    private fun makeVmc(): ByteArray {
        val b = ByteArray(131072)
        fun chk(i: Int) {
            var x = 0
            for (k in 0 until 127) x = x xor b[i * 128 + k].toInt()
            b[i * 128 + 127] = x.toByte()
        }
        b[0] = 'M'.code.toByte(); b[1] = 'C'.code.toByte(); chk(0)
        for (i in 1..15) {
            val o = i * 128
            b[o] = 0xA0.toByte(); b[o + 8] = 0xFF.toByte(); b[o + 9] = 0xFF.toByte(); chk(i)
        }
        for (i in 16..35) {
            val o = i * 128
            for (k in 0..3) b[o + k] = 0xFF.toByte(); chk(i)
        }
        System.arraycopy(b, 0, b, 63 * 128, 128)
        return b
    }

    private fun createOut(dir: DocumentFile, name: String): DocumentFile {
        dir.findFile(name)?.delete()
        val f = dir.createFile("application/octet-stream", name) ?: throw Exception("Não consegui criar $name")
        if (f.name != name) f.renameTo(name)
        return f
    }

    private fun sanitize(s: String, max: Int): String =
        s.replace(Regex("[^A-Za-z0-9 _.\\-]"), "_").trim().take(max).ifEmpty { "GAME" }

    private fun ensureFolder(dir: DocumentFile, name: String): DocumentFile =
        dir.findFile(name)?.takeIf { it.isDirectory }
            ?: dir.createDirectory(name)
            ?: throw Exception("Não consegui criar a pasta $name")

    private fun putSlots(sub: DocumentFile, label: String) {
        for (slot in listOf("SLOT0.VMC", "SLOT1.VMC")) {
            if (sub.findFile(slot) != null) {
                log("$label/$slot já existe, mantido (seus saves ficam intactos).")
            } else {
                val v = createOut(sub, slot)
                (contentResolver.openOutputStream(v.uri, "w") ?: throw Exception("Não abri $slot")).use { it.write(makeVmc()) }
                log("$label/$slot criado.")
            }
        }
    }

    private fun writeText(dir: DocumentFile, name: String, text: String) {
        val f = createOut(dir, name)
        (contentResolver.openOutputStream(f.uri, "w") ?: throw Exception("Não abri $name"))
            .use { it.write(text.toByteArray(Charsets.US_ASCII)) }
    }

    private fun convertAll(
        sel: List<DocumentFile>, idIn: String, nameIn: String,
        withHeader: Boolean, makeVmcFile: Boolean, multi: Boolean, share: Boolean
    ) {
        val dir = tree ?: throw Exception("Sem pasta")
        if (!multi) {
            val name = sanitize(nameIn, 20)
            val base = convertOne(sel[0], idIn, name, withHeader) { p -> runOnUiThread { bar.progress = p } }
            if (makeVmcFile) putSlots(ensureFolder(dir, base), base)
            return
        }
        if (sel.size < 2) throw Exception("Marque pelo menos 2 discos.")
        if (sel.size > 4) throw Exception("O POPStarter troca no máximo 4 discos.")
        val gname = sanitize(nameIn, 16)
        val bases = ArrayList<String>()
        for ((i, c) in sel.withIndex()) {
            log("--- Disco ${i + 1}: ${c.name} ---")
            bases.add(
                convertOne(c, "", "${gname}_CD${i + 1}", withHeader) { p ->
                    runOnUiThread { bar.progress = (i * 100 + p) / sel.size }
                }
            )
        }
        val list = bases.joinToString("\r\n") { "$it.VCD" }
        for ((i, b) in bases.withIndex()) {
            val sub = ensureFolder(dir, b)
            writeText(sub, "DISCS.TXT", list)
            if (share) writeText(sub, "VMCDIR.TXT", bases[0])
            if (makeVmcFile && (!share || i == 0)) putSlots(sub, b)
        }
        log("DISCS.TXT criado em cada pasta de disco.")
    }

    private fun convertOne(
        cue: DocumentFile, idIn: String, name: String, withHeader: Boolean, prog: (Int) -> Unit
    ): String {
        val dir = tree ?: throw Exception("Sem pasta")
        val text = (contentResolver.openInputStream(cue.uri) ?: throw Exception("Não abri o CUE"))
            .use { it.readBytes().toString(Charsets.UTF_8) }.removePrefix("\uFEFF")
        val (files, tracks) = parseCue(text)
        val all = dir.listFiles()
        val bins = files.map { n ->
            val base = n.substringAfterLast('/').substringAfterLast('\\')
            all.firstOrNull { it.isFile && it.name.equals(base, true) } ?: throw Exception("BIN não encontrado: $base")
        }
        val starts = LongArray(bins.size)
        var total = 0L
        for ((i, b) in bins.withIndex()) {
            if (b.length() % 2352L != 0L) throw Exception("${b.name}: tamanho não é múltiplo de 2352 (não é imagem RAW)")
            starts[i] = total
            total += b.length() / 2352L
        }
        log("${tracks.size} trilha(s), ${bins.size} arquivo(s) BIN, $total setores.")

        var id = idIn.uppercase()
        if (id.isEmpty()) {
            log("Detectando ID do jogo...")
            id = detectId(bins[0]) ?: throw Exception("Não consegui detectar o ID. Digite manualmente (ex: SLUS_007.76).")
            log("ID: $id")
        }
        val baseName = "$id.$name"
        val outName = "$baseName.VCD"

        val hdr = ByteArray(0x100000)
        if (withHeader) {
            val first = tracks.first()
            val last = tracks.last()
            fun ctl(t: TrackInfo): Byte = if (t.audio) 0x01 else 0x41
            var o = 0
            hdr[o] = ctl(first); hdr[o + 2] = 0xA0.toByte(); hdr[o + 7] = bcd(first.number); hdr[o + 8] = 0x20; o += 10
            hdr[o] = ctl(last); hdr[o + 2] = 0xA1.toByte(); hdr[o + 7] = bcd(last.number); o += 10
            hdr[o] = ctl(last); hdr[o + 2] = 0xA2.toByte(); putMsf(hdr, o + 7, total); o += 10
            for ((k, t) in tracks.withIndex()) {
                hdr[o] = ctl(t); hdr[o + 2] = bcd(t.number)
                val abs1 = starts[t.fileIdx] + t.idx1
                val abs0 = if (k == 0) -150L else if (t.idx0 >= 0) starts[t.fileIdx] + t.idx0 else abs1
                putMsf(hdr, o + 3, abs0)
                putMsf(hdr, o + 7, abs1)
                o += 10
            }
            hdr[0x400] = 0x6B; hdr[0x401] = 0x48; hdr[0x402] = 0x6E; hdr[0x403] = 0x22
            putLe32(hdr, 0x408, total)
            putLe32(hdr, 0x40C, total)
        } else {
            log("Aviso: sem cabeçalho o POPStarter não reconhece o VCD.")
        }

        log("Gravando $outName ...")
        val out = createOut(dir, outName)
        val totalBytes = bins.sumOf { it.length() }
        var done = 0L
        var lastPct = -1
        BufferedOutputStream(
            contentResolver.openOutputStream(out.uri, "w") ?: throw Exception("Não abri a saída"), 1 shl 20
        ).use { os ->
            if (withHeader) os.write(hdr)
            val buf = ByteArray(1 shl 20)
            for (b in bins) {
                (contentResolver.openInputStream(b.uri) ?: throw Exception("Não abri ${b.name}")).use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        val pct = (done * 100 / totalBytes).toInt()
                        if (pct != lastPct) { lastPct = pct; prog(pct) }
                    }
                }
            }
        }
        log("VCD pronto.")
        return baseName
    }
}te()
        val f = dir.createFile("application/octet-stream", name) ?: throw Exception("Não consegui criar $name")
        if (f.name != name) f.renameTo(name)
        return f
    }

    private fun sanitize(s: String, max: Int): String =
        s.replace(Regex("[^A-Za-z0-9 _.\\-]"), "_").trim().take(max).ifEmpty { "GAME" }

    private fun ensureFolder(dir: DocumentFile, name: String): DocumentFile =
        dir.findFile(name)?.takeIf { it.isDirectory }
            ?: dir.createDirectory(name)
            ?: throw Exception("Não consegui criar a pasta $name")

    private fun putSlots(sub: DocumentFile, label: String) {
        for (slot in listOf("SLOT0.VMC", "SLOT1.VMC")) {
            if (sub.findFile(slot) != null) {
                log("$label/$slot já existe, mantido (seus saves ficam intactos).")
            } else {
                val v = createOut(sub, slot)
                (contentResolver.openOutputStream(v.uri, "w") ?: throw Exception("Não abri $slot")).use { it.write(makeVmc()) }
                log("$label/$slot criado.")
            }
        }
    }

    private fun writeText(dir: DocumentFile, name: String, text: String) {
        val f = createOut(dir, name)
        (contentResolver.openOutputStream(f.uri, "w") ?: throw Exception("Não abri $name"))
            .use { it.write(text.toByteArray(Charsets.US_ASCII)) }
    }

    private fun convertAll(
        sel: List<DocumentFile>, idIn: String, nameIn: String,
        withHeader: Boolean, makeVmcFile: Boolean, multi: Boolean, share: Boolean
    ) {
        val dir = tree ?: throw Exception("Sem pasta")
        if (!multi) {
            val name = sanitize(nameIn, 20)
            val base = convertOne(sel[0], idIn, name, withHeader) { p -> runOnUiThread { bar.progress = p } }
            if (makeVmcFile) putSlots(ensureFolder(dir, base), base)
            return
        }
        if (sel.size < 2) throw Exception("Marque pelo menos 2 discos.")
        if (sel.size > 4) throw Exception("O POPStarter troca no máximo 4 discos.")
        val gname = sanitize(nameIn, 16)
        val bases = ArrayList<String>()
        for ((i, c) in sel.withIndex()) {
            log("--- Disco ${i + 1}: ${c.name} ---")
            bases.add(
                convertOne(c, "", "${gname}_CD${i + 1}", withHeader) { p ->
                    runOnUiThread { bar.progress = (i * 100 + p) / sel.size }
                }
            )
        }
        val list = bases.joinToString("\r\n") { "$it.VCD" }
        for ((i, b) in bases.withIndex()) {
            val sub = ensureFolder(dir, b)
            writeText(sub, "DISCS.TXT", list)
            if (share) writeText(sub, "VMCDIR.TXT", bases[0])
            if (makeVmcFile && (!share || i == 0)) putSlots(sub, b)
        }
        log("DISCS.TXT criado em cada pasta de disco.")
    }

    private fun convertOne(
        cue: DocumentFile, idIn: String, name: String, withHeader: Boolean, prog: (Int) -> Unit
    ): String {
        val dir = tree ?: throw Exception("Sem pasta")
        val text = (contentResolver.openInputStream(cue.uri) ?: throw Exception("Não abri o CUE"))
            .use { it.readBytes().toString(Charsets.UTF_8) }.removePrefix("\uFEFF")
        val (files, tracks) = parseCue(text)
        val all = dir.listFiles()
        val bins = files.map { n ->
            val base = n.substringAfterLast('/').substringAfterLast('\\')
            all.firstOrNull { it.isFile && it.name.equals(base, true) } ?: throw Exception("BIN não encontrado: $base")
        }
        val starts = LongArray(bins.size)
        var total = 0L
        for ((i, b) in bins.withIndex()) {
            if (b.length() % 2352L != 0L) throw Exception("${b.name}: tamanho não é múltiplo de 2352 (não é imagem RAW)")
            starts[i] = total
            total += b.length() / 2352L
        }
        log("${tracks.size} trilha(s), ${bins.size} arquivo(s) BIN, $total setores.")

        var id = idIn.uppercase()
        if (id.isEmpty()) {
            log("Detectando ID do jogo...")
            id = detectId(bins[0]) ?: throw Exception("Não consegui detectar o ID. Digite manualmente (ex: SLUS_007.76).")
            log("ID: $id")
        }
        val baseName = "$id.$name"
        val outName = "$baseName.VCD"

        val hdr = ByteArray(0x100000)
        if (withHeader) {
            val first = tracks.first()
            val last = tracks.last()
            fun ctl(t: TrackInfo): Byte = if (t.audio) 0x01 else 0x41
            var o = 0
            hdr[o] = ctl(first); hdr[o + 2] = 0xA0.toByte(); hdr[o + 7] = bcd(first.number); hdr[o + 8] = 0x20; o += 10
            hdr[o] = ctl(last); hdr[o + 2] = 0xA1.toByte(); hdr[o + 7] = bcd(last.number); o += 10
            hdr[o] = ctl(last); hdr[o + 2] = 0xA2.toByte(); putMsf(hdr, o + 7, total); o += 10
            for ((k, t) in tracks.withIndex()) {
                hdr[o] = ctl(t); hdr[o + 2] = bcd(t.number)
                val abs1 = starts[t.fileIdx] + t.idx1
                val abs0 = if (k == 0) -150L else if (t.idx0 >= 0) starts[t.fileIdx] + t.idx0 else abs1
                putMsf(hdr, o + 3, abs0)
                putMsf(hdr, o + 7, abs1)
                o += 10
            }
            hdr[0x400] = 0x6B; hdr[0x401] = 0x48; hdr[0x402] = 0x6E; hdr[0x403] = 0x22
            putLe32(hdr, 0x408, total)
            putLe32(hdr, 0x40C, total)
        } else {
            log("Aviso: sem cabeçalho o POPStarter não reconhece o VCD.")
        }

        log("Gravando $outName ...")
        val out = createOut(dir, outName)
        val totalBytes = bins.sumOf { it.length() }
        var done = 0L
        var lastPct = -1
        BufferedOutputStream(
            contentResolver.openOutputStream(out.uri, "w") ?: throw Exception("Não abri a saída"), 1 shl 20
        ).use { os ->
            if (withHeader) os.write(hdr)
            val buf = ByteArray(1 shl 20)
            for (b in bins) {
                (contentResolver.openInputStream(b.uri) ?: throw Exception("Não abri ${b.name}")).use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        val pct = (done * 100 / totalBytes).toInt()
                        if (pct != lastPct) { lastPct = pct; prog(pct) }
                    }
                }
            }
        }
        log("VCD pronto.")
        return baseName
    }
}
