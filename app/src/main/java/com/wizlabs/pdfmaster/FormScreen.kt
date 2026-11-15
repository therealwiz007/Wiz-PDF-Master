package com.wizlabs.pdfmaster

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FieldKind { TEXT, CHECK, RADIO, CHOICE }

/** One fillable field. A tick box holds "true" / "false"; the others hold their text or chosen option. */
data class FormField(
    val name: String,
    val label: String,
    val kind: FieldKind,
    val value: String,
    val options: List<String> = emptyList(),
    val multiline: Boolean = false
)

/** Reading and filling AcroForm fields (the common kind of PDF form). */
object PdfForms {
    sealed class Info {
        data object None : Info()
        /** Built with Adobe's XFA only: there are no ordinary fields to fill. */
        data object XfaOnly : Info()
        class Fields(val list: List<FormField>) : Info()
    }

    const val MAX_FIELDS = 400

    fun read(doc: PDDocument): Info {
        val form = doc.documentCatalog.acroForm ?: return Info.None
        val out = ArrayList<FormField>()
        for (f in form.fieldTree) {
            if (out.size >= MAX_FIELDS) break
            if (f.isReadOnly) continue
            val name = f.fullyQualifiedName ?: continue
            val label = f.alternateFieldName?.takeIf { it.isNotBlank() } ?: f.partialName ?: name
            runCatching {
                when (f) {
                    is PDTextField -> out += FormField(name, label, FieldKind.TEXT, f.value.orEmpty(), multiline = f.isMultiline)
                    is PDCheckBox -> out += FormField(name, label, FieldKind.CHECK, f.isChecked.toString())
                    is PDRadioButton -> {
                        val options = f.onValues.toList().sorted()
                        if (options.isNotEmpty()) out += FormField(name, label, FieldKind.RADIO, f.value.orEmpty().takeIf { it in options }.orEmpty(), options)
                    }
                    is PDChoice -> {
                        val options = f.optionsDisplayValues.orEmpty().ifEmpty { f.options.orEmpty() }
                        val exports = f.optionsExportValues.orEmpty()
                        val current = f.value.firstOrNull().orEmpty()
                        val shown = exports.indexOf(current).takeIf { it in options.indices }?.let { options[it] } ?: current
                        if (options.isNotEmpty()) out += FormField(name, label, FieldKind.CHOICE, shown, options)
                    }
                    else -> {}                               // buttons and signature fields are left alone
                }
            }
        }
        if (out.isEmpty()) return if (runCatching { form.hasXFA() }.getOrDefault(false)) Info.XfaOnly else Info.None
        if (runCatching { form.xfaIsDynamic() }.getOrDefault(false)) return Info.XfaOnly
        return Info.Fields(out)
    }

    private fun ensureHelvetica(form: PDAcroForm) {
        val res = form.defaultResources ?: PDResources().also { form.defaultResources = it }
        val helv = COSName.getPDFName("Helv")
        if (runCatching { res.getFont(helv) }.getOrNull() == null) res.put(helv, PDType1Font.HELVETICA)
    }

    /**
     * Write [values] (field name to value) into the form and rebuild how the fields look, so they
     * show in every viewer. With [flatten] the fields become plain page content that can no
     * longer be edited. Returns how many fields could not be set.
     */
    fun fill(doc: PDDocument, fields: List<FormField>, values: Map<String, String>, flatten: Boolean): Int {
        val form = doc.documentCatalog.acroForm ?: throw PdfOps.OpError("This PDF has no form to fill in.")
        var failed = 0
        for (spec in fields) {
            val v = values[spec.name] ?: continue
            if (v == spec.value) continue
            val f = form.getField(spec.name) ?: continue
            try {
                when (f) {
                    is PDTextField -> try {
                        f.value = v
                    } catch (e: Exception) {
                        // No usable font named in the field, or letters that font can't write: fall back to Helvetica.
                        ensureHelvetica(form)
                        f.defaultAppearance = "/Helv 0 Tf 0 g"
                        f.value = pdfSafe(v)
                    }
                    is PDCheckBox -> if (v == "true") f.check() else f.unCheck()
                    is PDRadioButton -> if (v.isNotEmpty()) f.value = v
                    is PDChoice -> {
                        val shown = f.optionsDisplayValues.orEmpty()
                        val exports = f.optionsExportValues.orEmpty()
                        val i = shown.indexOf(v)
                        f.setValue(if (i >= 0 && i < exports.size) exports[i] else v)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                failed++
            }
        }
        // A static XFA copy of the form would otherwise win over the values just set.
        runCatching { if (form.hasXFA()) form.xfa = null }
        val refreshed = runCatching { form.refreshAppearances() }.isSuccess
        form.needAppearances = !refreshed
        if (flatten) {
            try {
                form.flatten()
            } catch (e: Exception) {
                throw PdfOps.OpError("This form couldn't be flattened. Save it without \"Flatten\" instead.")
            }
        }
        return failed
    }

    suspend fun load(ctx: Context, ref: PdfRef, password: String?): Info = withContext(Dispatchers.IO) {
        val src = PdfOps.sourceFile(ctx, ref)
        try {
            PdfOps.load(ctx, src.file, password).use { read(it) }
        } finally {
            src.done()
        }
    }
}

@Composable
fun FormScreen(nav: Nav, ref: PdfRef, password: String?) {
    PdfGate(nav, ref, password) { src -> FormContent(nav, src) }
}

@Composable
private fun FormContent(nav: Nav, src: PdfSource) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<PdfForms.Info?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var flatten by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(src) {
        try {
            val i = PdfForms.load(ctx, src.ref, src.password)
            if (i is PdfForms.Info.Fields) i.list.forEach { values[it.name] = it.value }
            info = i
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = (e as? PdfOps.OpError)?.message ?: "This PDF couldn't be read."
        }
    }

    Column(Modifier.fillMaxSize().background(WizBg).imePadding()) {
        ToolTopBar("Fill form", PdfOps.baseName(src.ref), onBack = { nav.pop() })
        val i = info
        when {
            failed != null -> Message(failed!!)
            i == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = WizYellow) }
            i is PdfForms.Info.None -> Message("This PDF has no form fields. To write on it anyway, use Annotate and add notes.")
            i is PdfForms.Info.XfaOnly -> Message("This form was made with Adobe XFA, which is not supported. Open it in Adobe Acrobat to fill it in.")
            i is PdfForms.Info.Fields -> {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(i.list, key = { it.name }) { f ->
                        val v = values[f.name].orEmpty()
                        Surface(shape = RoundedCornerShape(16.dp), color = WizPanel, border = BorderStroke(1.dp, WizLine), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                when (f.kind) {
                                    FieldKind.TEXT -> OutlinedTextField(
                                        v, { values[f.name] = it.take(2000) }, label = { Text(f.label, maxLines = 1) },
                                        singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1, modifier = Modifier.fillMaxWidth()
                                    )
                                    FieldKind.CHECK -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(f.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        Switch(v == "true", { values[f.name] = it.toString() }, colors = SwitchDefaults.colors(checkedTrackColor = WizYellow, checkedThumbColor = WizInk))
                                    }
                                    FieldKind.RADIO, FieldKind.CHOICE -> {
                                        Text(f.label, style = MaterialTheme.typography.titleSmall)
                                        Spacer(Modifier.height(8.dp))
                                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            f.options.forEach { o -> WizChip(o, v == o, { values[f.name] = o }) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (i.list.size >= PdfForms.MAX_FIELDS) item {
                        Text("Only the first ${PdfForms.MAX_FIELDS} fields are shown.", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                    }
                }
                Column(Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Flatten", style = MaterialTheme.typography.titleSmall)
                            Text("Turns the answers into fixed page content that can't be edited", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                        }
                        Switch(flatten, { flatten = it }, colors = SwitchDefaults.colors(checkedTrackColor = WizYellow, checkedThumbColor = WizInk))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { askSave = true }, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = WizYellow, contentColor = WizInk),
                        shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(vertical = 16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save filled form", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    val fields = (info as? PdfForms.Info.Fields)?.list
    if (askSave && fields != null) SaveChoiceDialog(
        "Save filled form",
        if (flatten) "The answers are fixed onto the pages. Saving a copy keeps the empty form as it is." else "The fields stay editable in other PDF apps.",
        canReplace = src.ref is PdfRef.Local,
        onDismiss = { askSave = false }
    ) { replace ->
        askSave = false
        busy = true
        val snapshot = values.toMap()
        val flat = flatten
        scope.launch {
            var missed = 0
            val ok = PdfDecor.save(ctx, nav, src, "filled", replace, "Form saved") { doc, _ -> missed = PdfForms.fill(doc, fields, snapshot, flat) }
            if (ok && missed > 0) toast(ctx, "$missed field${if (missed == 1) "" else "s"} couldn't be filled in")
            busy = false
        }
    }
    if (busy) BusyDialogPlain("Saving form", "Writing your answers…")
}

@Composable
private fun Message(text: String) {
    Text(text, color = WizMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
}
