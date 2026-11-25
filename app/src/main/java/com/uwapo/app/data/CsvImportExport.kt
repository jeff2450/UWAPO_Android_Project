package com.uwapo.app.data

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object CsvImportExport {
    data class ImportResult(
        val membersAdded: Int,
        val paymentsAdded: Int,
        val rowsSkipped: Int,
        val sheetDeclaredTotal: Long? = null,
        val calculatedTotal: Long = 0L,
        val totalMismatchWarning: String? = null
    )

    private val MONTH_MAP = mapOf(
        "january" to 1, "jan" to 1, "januari" to 1,
        "february" to 2, "feb" to 2, "februari" to 2,
        "march" to 3, "mar" to 3, "machi" to 3,
        "april" to 4, "apr" to 4, "aprili" to 4,
        "may" to 5, "mei" to 5,
        "june" to 6, "jun" to 6, "juni" to 6,
        "july" to 7, "jul" to 7, "julai" to 7,
        "august" to 8, "aug" to 8, "agosti" to 8,
        "september" to 9, "sept" to 9, "sep" to 9, "septemba" to 9,
        "october" to 10, "oct" to 10, "oktoba" to 10,
        "november" to 11, "nov" to 11, "novemba" to 11,
        "december" to 12, "dec" to 12, "desemba" to 12
    )

    data class TermMemberExportRow(
        val memberNo: String,
        val fullName: String,
        val monthlyAmounts: List<Long>,
        val totalAmount: Long
    )

    data class ArrearsMemberExportRow(
        val memberNo: String,
        val fullName: String,
        val phone: String,
        val status: String,
        val amountPaid: Long = 0L,
        val shortfall: Long = 0L
    )

    fun export(members: List<MemberEntity>, payments: List<PaymentEntity>): String {
        val header = listOf("member_no", "full_name", "phone", "join_date", "status", "batch_year", "period_year", "period_month", "amount_paid_tzs", "paid_at", "payment_method", "receipt_reference", "source_note")
        val memberMap = members.associateBy { it.id }
        val lines = mutableListOf(header.joinToString(",") { csv(it) })
        payments.forEach { payment ->
            val member = memberMap[payment.memberId] ?: return@forEach
            val paidDate = java.time.Instant.ofEpochMilli(payment.paidAt).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate().toString()
            val joinedDate = java.time.Instant.ofEpochMilli(member.joinedAt).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate().toString()
            lines += listOf(member.memberNo, member.fullName, member.phone, joinedDate, member.status,
                member.batchYear.toString(), payment.periodYear.toString(), payment.periodMonth.toString(), payment.amountTzs.toString(), paidDate,
                payment.method, payment.receiptNo, "Exported from UWAPO app (${payment.syncStatus})").joinToString(",") { csv(it) }
        }
        val paidMemberIds = payments.map { it.memberId }.toSet()
        members.filter { it.id !in paidMemberIds }.forEach { member ->
            val joinedDate = java.time.Instant.ofEpochMilli(member.joinedAt).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate().toString()
            lines += listOf(member.memberNo, member.fullName, member.phone, joinedDate, member.status, member.batchYear.toString(), "", "", "", "", "", "", "No payment row").joinToString(",") { csv(it) }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    fun exportToXlsx(members: List<MemberEntity>, payments: List<PaymentEntity>): ByteArray {
        val header = listOf("member_no", "full_name", "phone", "join_date", "status", "batch_year", "period_year", "period_month", "amount_paid_tzs", "paid_at", "payment_method", "receipt_reference", "source_note")
        val memberMap = members.associateBy { it.id }
        val rows = mutableListOf<List<Any?>>(header)
        payments.forEach { payment ->
            val member = memberMap[payment.memberId] ?: return@forEach
            val paidDate = java.time.Instant.ofEpochMilli(payment.paidAt).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate().toString()
            val joinedDate = java.time.Instant.ofEpochMilli(member.joinedAt).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate().toString()
            rows.add(listOf(member.memberNo, member.fullName, member.phone, joinedDate, member.status,
                member.batchYear, payment.periodYear, payment.periodMonth, payment.amountTzs, paidDate,
                payment.method, payment.receiptNo, "Exported from UWAPO app (${payment.syncStatus})"))
        }
        val paidMemberIds = payments.map { it.memberId }.toSet()
        members.filter { it.id !in paidMemberIds }.forEach { member ->
            val joinedDate = java.time.Instant.ofEpochMilli(member.joinedAt).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate().toString()
            rows.add(listOf(member.memberNo, member.fullName, member.phone, joinedDate, member.status, member.batchYear, "", "", "", "", "", "", "No payment row"))
        }
        return createXlsx("Members & Payments", rows)
    }

    fun exportTermReportToXlsx(
        termTitle: String,
        year: Int,
        monthLabels: List<String>,
        memberRows: List<TermMemberExportRow>,
        grandTotalMonthly: List<Long>,
        grandTotalTerm: Long,
        expectedRevenue: Long,
        collectedRevenue: Long,
        estimatedArrears: Long
    ): ByteArray {
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf("Financial & Contribution Reports - Term Report"))
        rows.add(listOf("Term:", "$termTitle $year"))
        rows.add(listOf())
        rows.add(listOf("SUMMARY METRICS", "AMOUNT (TZS)"))
        rows.add(listOf("Expected Revenue", expectedRevenue))
        rows.add(listOf("Term Collected", collectedRevenue))
        rows.add(listOf("Estimated Arrears", estimatedArrears))
        rows.add(listOf())
        rows.add(listOf("MEMBER CONTRIBUTIONS GRID ($termTitle $year)"))

        val header = mutableListOf<Any?>("Member No", "Full Name")
        header.addAll(monthLabels)
        header.add("Total (TZS)")
        rows.add(header)

        memberRows.forEach { m ->
            val row = mutableListOf<Any?>(m.memberNo, m.fullName)
            row.addAll(m.monthlyAmounts)
            row.add(m.totalAmount)
            rows.add(row)
        }

        val totalsRow = mutableListOf<Any?>("GRAND TOTAL", "")
        totalsRow.addAll(grandTotalMonthly)
        totalsRow.add(grandTotalTerm)
        rows.add(totalsRow)

        return createXlsx("Term Report", rows)
    }

    fun exportTermReportToCsv(
        termTitle: String,
        year: Int,
        monthLabels: List<String>,
        memberRows: List<TermMemberExportRow>,
        grandTotalMonthly: List<Long>,
        grandTotalTerm: Long,
        expectedRevenue: Long,
        collectedRevenue: Long,
        estimatedArrears: Long
    ): String {
        val lines = mutableListOf<String>()
        lines.add(csv("Financial & Contribution Reports - Term Report"))
        lines.add(listOf("Term:", "$termTitle $year").joinToString(",") { csv(it) })
        lines.add("")
        lines.add(listOf("SUMMARY METRICS", "AMOUNT (TZS)").joinToString(",") { csv(it) })
        lines.add(listOf("Expected Revenue", expectedRevenue.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Term Collected", collectedRevenue.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Estimated Arrears", estimatedArrears.toString()).joinToString(",") { csv(it) })
        lines.add("")
        lines.add(csv("MEMBER CONTRIBUTIONS GRID ($termTitle $year)"))

        val header = mutableListOf("Member No", "Full Name")
        header.addAll(monthLabels)
        header.add("Total (TZS)")
        lines.add(header.joinToString(",") { csv(it) })

        memberRows.forEach { m ->
            val row = mutableListOf(m.memberNo, m.fullName)
            row.addAll(m.monthlyAmounts.map { it.toString() })
            row.add(m.totalAmount.toString())
            lines.add(row.joinToString(",") { csv(it) })
        }

        val totalsRow = mutableListOf("GRAND TOTAL", "")
        totalsRow.addAll(grandTotalMonthly.map { it.toString() })
        totalsRow.add(grandTotalTerm.toString())
        lines.add(totalsRow.joinToString(",") { csv(it) })

        return lines.joinToString("\r\n") + "\r\n"
    }

    fun exportMonthlyReportToXlsx(
        periodTitle: String,
        collectionsTotal: Long,
        expensesTotal: Long,
        netMovement: Long,
        expectedRevenue: Long,
        estimatedArrears: Long,
        memberCount: Int,
        batchBreakdown: Map<String, Long>,
        arrearsMembers: List<ArrearsMemberExportRow>
    ): ByteArray {
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf("Financial & Contribution Reports - Monthly Report"))
        rows.add(listOf("Period:", periodTitle))
        rows.add(listOf())
        rows.add(listOf("FINANCIAL SUMMARY", "AMOUNT (TZS)"))
        rows.add(listOf("Collections Total", collectionsTotal))
        rows.add(listOf("Expenses Total", expensesTotal))
        rows.add(listOf("Net Movement", netMovement))
        rows.add(listOf("Expected Contributions", expectedRevenue))
        rows.add(listOf("Estimated Arrears", estimatedArrears))
        rows.add(listOf("Members Count", memberCount))
        rows.add(listOf())

        if (batchBreakdown.isNotEmpty()) {
            rows.add(listOf("BATCH COLLECTION BREAKDOWN"))
            rows.add(listOf("Batch Year", "Total Collected (TZS)"))
            batchBreakdown.forEach { (bYear, amt) ->
                rows.add(listOf("Batch $bYear", amt))
            }
            rows.add(listOf())
        }

        if (arrearsMembers.isNotEmpty()) {
            rows.add(listOf("MEMBERS IN ARREARS ($periodTitle)"))
            rows.add(listOf("Member No", "Full Name", "Phone", "Status"))
            arrearsMembers.forEach { m ->
                rows.add(listOf(m.memberNo, m.fullName, m.phone, m.status))
            }
        }

        return createXlsx("Monthly Report", rows)
    }

    fun exportMonthlyReportToCsv(
        periodTitle: String,
        collectionsTotal: Long,
        expensesTotal: Long,
        netMovement: Long,
        expectedRevenue: Long,
        estimatedArrears: Long,
        memberCount: Int,
        batchBreakdown: Map<String, Long>,
        arrearsMembers: List<ArrearsMemberExportRow>
    ): String {
        val lines = mutableListOf<String>()
        lines.add(csv("Financial & Contribution Reports - Monthly Report"))
        lines.add(listOf("Period:", periodTitle).joinToString(",") { csv(it) })
        lines.add("")
        lines.add(listOf("FINANCIAL SUMMARY", "AMOUNT (TZS)").joinToString(",") { csv(it) })
        lines.add(listOf("Collections Total", collectionsTotal.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Expenses Total", expensesTotal.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Net Movement", netMovement.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Expected Contributions", expectedRevenue.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Estimated Arrears", estimatedArrears.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Members Count", memberCount.toString()).joinToString(",") { csv(it) })
        lines.add("")

        if (batchBreakdown.isNotEmpty()) {
            lines.add(csv("BATCH COLLECTION BREAKDOWN"))
            lines.add(listOf("Batch Year", "Total Collected (TZS)").joinToString(",") { csv(it) })
            batchBreakdown.forEach { (bYear, amt) ->
                lines.add(listOf("Batch $bYear", amt.toString()).joinToString(",") { csv(it) })
            }
            lines.add("")
        }

        if (arrearsMembers.isNotEmpty()) {
            lines.add(csv("MEMBERS IN ARREARS ($periodTitle)"))
            lines.add(listOf("Member No", "Full Name", "Phone", "Status").joinToString(",") { csv(it) })
            arrearsMembers.forEach { m ->
                lines.add(listOf(m.memberNo, m.fullName, m.phone, m.status).joinToString(",") { csv(it) })
            }
        }

        return lines.joinToString("\r\n") + "\r\n"
    }

    fun exportAnnualReportToXlsx(
        year: Int,
        collectionsTotal: Long,
        expensesTotal: Long,
        netMovement: Long,
        expectedRevenue: Long,
        estimatedShortfall: Long,
        monthlyBreakdown: Map<String, Long>,
        arrearsMembers: List<ArrearsMemberExportRow>
    ): ByteArray {
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf("Financial & Contribution Reports - Annual Report"))
        rows.add(listOf("Year:", year))
        rows.add(listOf())
        rows.add(listOf("FINANCIAL SUMMARY", "AMOUNT (TZS)"))
        rows.add(listOf("Annual Collections Total", collectionsTotal))
        rows.add(listOf("Annual Expenses Total", expensesTotal))
        rows.add(listOf("Annual Net Movement", netMovement))
        rows.add(listOf("Expected Revenue", expectedRevenue))
        rows.add(listOf("Estimated Shortfall", estimatedShortfall))
        rows.add(listOf())

        rows.add(listOf("MONTHLY COLLECTIONS BREAKDOWN ($year)"))
        rows.add(listOf("Month", "Amount (TZS)"))
        monthlyBreakdown.forEach { (monthName, amt) ->
            rows.add(listOf(monthName, amt))
        }
        rows.add(listOf())

        if (arrearsMembers.isNotEmpty()) {
            rows.add(listOf("MEMBERS IN ANNUAL ARREARS ($year)"))
            rows.add(listOf("Member No", "Full Name", "Phone", "Total Paid (TZS)", "Shortfall (TZS)"))
            arrearsMembers.forEach { m ->
                rows.add(listOf(m.memberNo, m.fullName, m.phone, m.amountPaid, m.shortfall))
            }
        }

        return createXlsx("Annual Report $year", rows)
    }

    fun exportAnnualReportToCsv(
        year: Int,
        collectionsTotal: Long,
        expensesTotal: Long,
        netMovement: Long,
        expectedRevenue: Long,
        estimatedShortfall: Long,
        monthlyBreakdown: Map<String, Long>,
        arrearsMembers: List<ArrearsMemberExportRow>
    ): String {
        val lines = mutableListOf<String>()
        lines.add(csv("Financial & Contribution Reports - Annual Report"))
        lines.add(listOf("Year:", year.toString()).joinToString(",") { csv(it) })
        lines.add("")
        lines.add(listOf("FINANCIAL SUMMARY", "AMOUNT (TZS)").joinToString(",") { csv(it) })
        lines.add(listOf("Annual Collections Total", collectionsTotal.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Annual Expenses Total", expensesTotal.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Annual Net Movement", netMovement.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Expected Revenue", expectedRevenue.toString()).joinToString(",") { csv(it) })
        lines.add(listOf("Estimated Shortfall", estimatedShortfall.toString()).joinToString(",") { csv(it) })
        lines.add("")

        lines.add(csv("MONTHLY COLLECTIONS BREAKDOWN ($year)"))
        lines.add(listOf("Month", "Amount (TZS)").joinToString(",") { csv(it) })
        monthlyBreakdown.forEach { (monthName, amt) ->
            lines.add(listOf(monthName, amt.toString()).joinToString(",") { csv(it) })
        }
        lines.add("")

        if (arrearsMembers.isNotEmpty()) {
            lines.add(csv("MEMBERS IN ANNUAL ARREARS ($year)"))
            lines.add(listOf("Member No", "Full Name", "Phone", "Total Paid (TZS)", "Shortfall (TZS)").joinToString(",") { csv(it) })
            arrearsMembers.forEach { m ->
                lines.add(listOf(m.memberNo, m.fullName, m.phone, m.amountPaid.toString(), m.shortfall.toString()).joinToString(",") { csv(it) })
            }
        }

        return lines.joinToString("\r\n") + "\r\n"
    }

    fun createXlsx(sheetName: String = "Report", rows: List<List<Any?>>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            val typesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""".trimIndent()
            zos.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
            zos.write(typesXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val relsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent()
            zos.putNextEntry(java.util.zip.ZipEntry("_rels/.rels"))
            zos.write(relsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val wbRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""".trimIndent()
            zos.putNextEntry(java.util.zip.ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(wbRelsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val safeName = sheetName.replace(Regex("[^a-zA-Z0-9 ]"), "").ifBlank { "Sheet1" }
            val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="$safeName" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>""".trimIndent()
            zos.putNextEntry(java.util.zip.ZipEntry("xl/workbook.xml"))
            zos.write(workbookXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>""".trimIndent()
            zos.putNextEntry(java.util.zip.ZipEntry("xl/styles.xml"))
            zos.write(stylesXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            rows.forEachIndexed { rIdx, row ->
                val rowNum = rIdx + 1
                sb.append("""<row r="$rowNum">""")
                row.forEachIndexed { cIdx, cell ->
                    if (cell != null) {
                        val colName = getExcelColName(cIdx) + rowNum
                        when (cell) {
                            is Number -> sb.append("""<c r="$colName"><v>$cell</v></c>""")
                            else -> {
                                val esc = escapeXml(cell.toString())
                                sb.append("""<c r="$colName" t="inlineStr"><is><t>$esc</t></is></c>""")
                            }
                        }
                    }
                }
                sb.append("""</row>""")
            }
            sb.append("""</sheetData></worksheet>""")
            zos.write(sb.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun getExcelColName(col: Int): String {
        var c = col
        val sb = StringBuilder()
        while (c >= 0) {
            sb.insert(0, ('A'.code + (c % 26)).toChar())
            c = (c / 26) - 1
        }
        return sb.toString()
    }

    private fun escapeXml(str: String): String =
        str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    suspend fun import(input: InputStream, dao: UWAPODao, targetStartYear: Int? = null): ImportResult {
        val sheets = parseInputSheets(input)
        if (sheets.isEmpty() || sheets.all { it.isEmpty() }) return ImportResult(0, 0, 0)

        val isMatrix = sheets.any { sheetHasMatrixHeader(it) }
        return if (isMatrix) {
            importMatrixLedger(sheets, dao, targetStartYear)
        } else {
            importStandardRows(sheets.flatten(), dao, targetStartYear)
        }
    }

    private fun sheetHasMatrixHeader(sheet: List<List<String>>): Boolean {
        for (row in sheet.take(50)) {
            var monthCount = 0
            for (cell in row) {
                if (MONTH_MAP.containsKey(cell.trim().lowercase(Locale.ROOT))) {
                    monthCount++
                }
            }
            if (monthCount >= 2) return true
        }
        return false
    }

    private fun normalizeMemberName(name: String): String {
        return name.replace(".", " ")
            .replace("-", " ")
            .replace("_", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** Same person written slightly differently (typos like MARUA/MARUWA, or a middle name shortened to an initial). */
    private fun namesLikelySame(a: String, b: String): Boolean {
        if (a.length >= 8 && b.length >= 8 && editDistance(a, b) <= 2) return true
        val ta = a.split(" ").filter { it.isNotBlank() }
        val tb = b.split(" ").filter { it.isNotBlank() }
        if (ta.size < 2 || tb.size < 2 || ta.size == tb.size && ta == tb) return ta == tb
        if (ta.first() != tb.first() || ta.last() != tb.last()) return false
        val midA = ta.drop(1).dropLast(1)
        val midB = tb.drop(1).dropLast(1)
        val (short, long) = if (midA.size <= midB.size) midA to midB else midB to midA
        return short.all { sm -> long.any { lm -> lm.startsWith(sm) || sm.startsWith(lm) } }
    }

    private suspend fun findOrRegisterMember(
        existingMembers: MutableList<MemberEntity>,
        num: String,
        fullName: String,
        year: Int,
        phone: String = "",
        status: String = "active",
        dao: UWAPODao
    ): Pair<MemberEntity, Boolean> {
        // Batches are independent: only members of THIS batch (year) are ever matched or created.
        dao.insertBatchIgnore(BatchEntity(year))
        val normName = normalizeMemberName(fullName)
        val matchedByName = if (normName.isNotBlank()) {
            existingMembers.find { normalizeMemberName(it.fullName) == normName }
                ?: existingMembers.find { namesLikelySame(normalizeMemberName(it.fullName), normName) }
        } else null

        // Member numbers are NOT stable between sheets (Term 2 is numbered differently from Term 1),
        // so a number is only used to match when the row has no name at all.
        val matchedByNum = if (matchedByName == null && normName.isBlank() && num.isNotBlank()) {
            existingMembers.find { it.memberNo == num }
        } else null

        val matched = matchedByName ?: matchedByNum
        if (matched != null) {
            return Pair(matched, false)
        }

        var memberNo = num.ifBlank { fullName.replace(" ", "_").take(15) }
        if (existingMembers.any { it.memberNo == memberNo }) {
            val maxNo = existingMembers.mapNotNull { it.memberNo.toIntOrNull() }.maxOrNull() ?: 0
            memberNo = (maxNo + 1).toString()
        }
        val memberId = UUID.nameUUIDFromBytes("UWAPO-MEMBER:$year:$memberNo".toByteArray(Charsets.UTF_8)).toString()
        val joinDate = LocalDate.of(year, 7, 1)
        val newMember = MemberEntity(
            id = memberId,
            memberNo = memberNo,
            fullName = fullName.ifBlank { memberNo },
            phone = phone,
            joinedAt = joinDate.atStartOfDay(ZoneId.of("Africa/Dar_es_Salaam")).toInstant().toEpochMilli(),
            status = status,
            syncStatus = "pending",
            batchYear = year
        )

        val inserted = dao.insertMemberIgnore(newMember)
        val isAdded = inserted != -1L
        val created = dao.getMemberByNumber(year, memberNo) ?: dao.getMember(memberId) ?: newMember
        existingMembers.add(created)
        return Pair(created, isAdded)
    }

    private suspend fun importMatrixLedger(sheets: List<List<List<String>>>, dao: UWAPODao, targetStartYear: Int? = null): ImportResult {
        val year = targetStartYear ?: detectYear(sheets, targetStartYear)
        // Re-importing the same ledger must replace earlier imported payments, never add on top of them.
        dao.deleteImportedPaymentsForYear(year)
        val existingMembers = dao.getMembersForBatchSync(year).toMutableList()
        var membersAdded = 0
        var paymentsAdded = 0
        var calculatedTotal = 0L
        var sheetDeclaredTotal: Long? = null
        val skipped = 0

        for ((sheetIdx, sheet) in sheets.withIndex()) {
            val detectedCols = mutableMapOf<Int, Int>()
            for (row in sheet.take(20)) {
                for ((cIdx, cell) in row.withIndex()) {
                    val m = MONTH_MAP[cell.trim().lowercase(Locale.ROOT)]
                    if (m != null && cIdx !in detectedCols) {
                        detectedCols[cIdx] = m
                    }
                }
            }

            // Only columns whose header is a month name are payments. Never guess extra columns:
            // the "JUMLA ADA" / "JUMLA" column is the row total and was previously imported as May/December.
            val monthCols = detectedCols.toMutableMap()
            if (monthCols.isEmpty()) {
                val fallback = if (sheetIdx == 0) mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 6)
                else mapOf(1 to 7, 2 to 8, 3 to 9, 4 to 10, 5 to 11, 6 to 12)
                monthCols.putAll(fallback)
            }

            if (monthCols.isEmpty()) continue

            var currentNum = ""
            var currentName = ""
            val blockMonthPayments = mutableMapOf<Int, Long>()

            suspend fun commitBlock() {
                val name = currentName.ifBlank { currentNum }
                if (name.isNotBlank() && blockMonthPayments.isNotEmpty()) {
                    val (actualMember, isNew) = findOrRegisterMember(existingMembers, currentNum, name, year, dao = dao)
                    if (isNew) membersAdded++
                    val memberNo = actualMember.memberNo

                    for ((monthNum, amount) in blockMonthPayments) {
                        if (amount > 0) {
                            val paidDate = LocalDate.of(year, monthNum, 1)
                            val receipt = "IMPORT-$memberNo-$year-${monthNum.toString().padStart(2, '0')}"
                            val key = "$memberNo|$year|$monthNum|$amount|$paidDate|$receipt"
                            val paymentId = UUID.nameUUIDFromBytes("UWAPO-PAYMENT:$key".toByteArray(Charsets.UTF_8)).toString()
                            val payment = PaymentEntity(
                                paymentId, actualMember.id, amount, year, monthNum,
                                paidDate.atStartOfDay(ZoneId.of("Africa/Dar_es_Salaam")).toInstant().toEpochMilli(),
                                "cash", receipt, paymentId, "pending"
                            )
                            if (dao.insertPaymentIgnore(payment) != -1L) {
                                paymentsAdded++
                                calculatedTotal += amount
                            }
                        }
                    }
                }
                currentNum = ""
                currentName = ""
                blockMonthPayments.clear()
            }

            for (row in sheet) {
                val rawCell = row.getOrNull(0)?.trim() ?: ""
                val rawUpper = rawCell.uppercase(Locale.ROOT)

                if (rawUpper in listOf("TOTAL", "JUMLA", "JUNE", "JINA", "ADA", "JUMLA ADA", "JULY", "AUG", "SEPT", "OCT", "NOV", "DEC") || rawUpper.startsWith("TOTAL") || rawUpper.startsWith("JUMLA")) {
                    if (rawUpper in listOf("TOTAL", "JUMLA", "JUMLA ADA") || rawUpper.startsWith("TOTAL") || rawUpper.startsWith("JUMLA")) {
                        commitBlock()
                        val monthColSums = monthCols.mapNotNull { (cIdx, _) ->
                            row.getOrNull(cIdx)?.replace(",", "")?.trim()?.toDoubleOrNull()?.toLong()
                        }.filter { it > 0 }
                        if (monthColSums.isNotEmpty()) {
                            val sum = monthColSums.sum()
                            if (sum > 0) sheetDeclaredTotal = (sheetDeclaredTotal ?: 0L) + sum
                        } else {
                            val allNums = row.mapNotNull { it.replace(",", "").trim().toDoubleOrNull()?.toLong() }.filter { it > 0 }
                            if (allNums.isNotEmpty()) {
                                sheetDeclaredTotal = (sheetDeclaredTotal ?: 0L) + allNums.maxOrNull()!!
                            }
                        }
                    }
                    continue
                }

                val (num, name) = parseMemberCell(rawCell)
                val isNumRow = rawCell.matches(Regex("""^\d+\.?$"""))
                val isNameRow = rawCell.isNotBlank() && !isNumRow && rawCell.length > 1

                if (isNumRow || isNameRow) {
                    if ((isNumRow && currentNum.isNotBlank() && currentNum != num) ||
                        (isNameRow && currentName.isNotBlank() && currentName != name)) {
                        commitBlock()
                    }
                    if (num.isNotBlank()) currentNum = num
                    if (name.isNotBlank()) currentName = name
                }

                for ((cIdx, monthNum) in monthCols) {
                    val amtStr = row.getOrNull(cIdx)?.replace(",", "")?.trim() ?: ""
                    val amount = amtStr.toDoubleOrNull()?.toLong() ?: 0L
                    if (amount > 0) {
                        val prev = blockMonthPayments[monthNum] ?: 0L
                        blockMonthPayments[monthNum] = maxOf(prev, amount)
                    }
                }
            }

            commitBlock()
        }

        val warning = if (sheetDeclaredTotal != null && sheetDeclaredTotal > 0 && calculatedTotal != sheetDeclaredTotal) {
            val fmtDeclared = java.text.NumberFormat.getNumberInstance(Locale.US).format(sheetDeclaredTotal)
            val fmtCalc = java.text.NumberFormat.getNumberInstance(Locale.US).format(calculatedTotal)
            val diff = java.text.NumberFormat.getNumberInstance(Locale.US).format(kotlin.math.abs(sheetDeclaredTotal - calculatedTotal))
            "Warning: Sheet TOTAL row shows TZS $fmtDeclared, but imported payments sum to TZS $fmtCalc (Difference: TZS $diff). Please verify sheet rows."
        } else null

        return ImportResult(membersAdded, paymentsAdded, skipped, sheetDeclaredTotal, calculatedTotal, warning)
    }

    private suspend fun importStandardRows(rows: List<List<String>>, dao: UWAPODao, targetStartYear: Int? = null): ImportResult {
        if (rows.isEmpty()) return ImportResult(0, 0, 0)
        val header = rows.first().map { it.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT) }
        fun index(vararg names: String): Int = header.indexOfFirst { it in names }
        val iNo = index("member_no", "member number", "membership number", "member_id", "member id", "no", "no.")
        var iName = index("full_name", "name", "member name", "full name", "jina")
        if (iName < 0 && iNo >= 0) iName = iNo
        if (iNo < 0 && iName < 0) throw IllegalArgumentException("File must contain member_no or full_name columns.")

        val iPhone = index("phone", "phone_number", "phone number", "mobile")
        val iJoin = index("join_date", "joined_at", "join date", "date joined")
        val iStatus = index("status")
        val iBatch = index("batch_year", "batch")
        val iYear = index("period_year", "year")
        val iMonth = index("period_month", "month")
        val iAmount = index("amount_paid_tzs", "amount", "paid", "amount paid", "amount_paid")
        val iPaidAt = index("paid_at", "payment_date", "paid date", "date")
        val iMethod = index("payment_method", "method", "payment method")
        val iReceipt = index("receipt_reference", "receipt_no", "receipt", "receipt no")

        var membersAdded = 0
        var paymentsAdded = 0
        var calculatedTotal = 0L
        var sheetDeclaredTotal: Long? = null
        var skipped = 0

        // One member list per batch year, so the same person in two batches stays two separate members.
        val membersByBatch = mutableMapOf<Int, MutableList<MemberEntity>>()
        rows.drop(1).forEach { row ->
            fun value(i: Int) = if (i >= 0 && i < row.size) row[i].trim() else ""
            val rawName = value(iName)
            val rawNo = value(iNo)
            val (numFromCell, nameFromCell) = parseMemberCell(if (rawName.isNotBlank()) rawName else rawNo)
            val memberNo = cleanStr(if (rawNo.isNotBlank()) rawNo else numFromCell)
            val fullName = if (rawName.isNotBlank() && iName != iNo) rawName else nameFromCell

            if (rawName.contains("TOTAL", ignoreCase = true) || rawName.contains("JUMLA", ignoreCase = true) ||
                rawNo.contains("TOTAL", ignoreCase = true) || rawNo.contains("JUMLA", ignoreCase = true)) {
                val declaredAmt = value(iAmount).replace(",", "").replace("TZS", "", ignoreCase = true).trim().toDoubleOrNull()?.toLong()
                if (declaredAmt != null && declaredAmt > 0) {
                    sheetDeclaredTotal = (sheetDeclaredTotal ?: 0L) + declaredAmt
                }
                return@forEach
            }

            if (memberNo.isBlank() && fullName.isBlank()) { skipped++; return@forEach }
            val rowPaymentYear = cleanStr(value(iYear)).toIntOrNull()
            val batchYear = targetStartYear
                ?: cleanStr(value(iBatch)).toIntOrNull()?.takeIf { it in 2000..2100 }
                ?: rowPaymentYear?.takeIf { it in 2000..2100 }
                ?: LocalDate.now().year
            val defaultJoinYear = batchYear
            val existingMembers = membersByBatch.getOrPut(batchYear) { dao.getMembersForBatchSync(batchYear).toMutableList() }
            val (actualMember, isNew) = findOrRegisterMember(
                existingMembers = existingMembers,
                num = memberNo,
                fullName = fullName.ifBlank { memberNo },
                year = defaultJoinYear,
                phone = cleanStr(value(iPhone)),
                status = value(iStatus).ifBlank { "active" }.lowercase(),
                dao = dao
            )
            if (isNew) membersAdded++

            val actualNo = actualMember.memberNo
            val amount = value(iAmount).replace(",", "").replace("TZS", "", ignoreCase = true).trim().let { it.toDoubleOrNull()?.toLong() ?: it.toLongOrNull() } ?: 0L
            val year = rowPaymentYear ?: targetStartYear
            val month = cleanStr(value(iMonth)).toIntOrNull()
            // A payment always belongs to the batch of its member; rows for another year are not mixed in.
            if (amount > 0 && year != null && year == batchYear && year in 2000..2100 && month != null && month in 1..12) {
                val paidDate = parseDate(value(iPaidAt)) ?: LocalDate.of(year, month, 1)
                val method = value(iMethod).ifBlank { "cash" }.lowercase().replace(' ', '_')
                val receipt = value(iReceipt).ifBlank { "IMPORT-$actualNo-$year-${month.toString().padStart(2,'0')}" }
                val key = "$actualNo|$year|$month|$amount|$paidDate|$receipt"
                val paymentId = UUID.nameUUIDFromBytes("UWAPO-PAYMENT:$key".toByteArray(Charsets.UTF_8)).toString()
                val payment = PaymentEntity(paymentId, actualMember.id, amount, year, month,
                    paidDate.atStartOfDay(ZoneId.of("Africa/Dar_es_Salaam")).toInstant().toEpochMilli(),
                    if (method in listOf("cash", "bank", "mobile_money", "other")) method else "other",
                    receipt, paymentId, "pending")
                if (dao.insertPaymentIgnore(payment) != -1L) {
                    paymentsAdded++
                    calculatedTotal += amount
                }
            }
        }

        val warning = if (sheetDeclaredTotal != null && sheetDeclaredTotal > 0 && calculatedTotal != sheetDeclaredTotal) {
            val fmtDeclared = java.text.NumberFormat.getNumberInstance(Locale.US).format(sheetDeclaredTotal)
            val fmtCalc = java.text.NumberFormat.getNumberInstance(Locale.US).format(calculatedTotal)
            val diff = java.text.NumberFormat.getNumberInstance(Locale.US).format(kotlin.math.abs(sheetDeclaredTotal - calculatedTotal))
            "Warning: Sheet TOTAL row shows TZS $fmtDeclared, but imported payments sum to TZS $fmtCalc (Difference: TZS $diff). Please verify sheet rows."
        } else null

        return ImportResult(membersAdded, paymentsAdded, skipped, sheetDeclaredTotal, calculatedTotal, warning)
    }

    private fun detectYear(sheets: List<List<List<String>>>, fallbackYear: Int? = null): Int {
        val yearRegex = Regex("""\b(20\d\d)\b""")
        for (sheet in sheets) {
            for (row in sheet.take(10)) {
                for (cell in row) {
                    val m = yearRegex.find(cell)
                    if (m != null) {
                        val y = m.groupValues[1].toIntOrNull()
                        if (y != null && y in 2000..2100) return y
                    }
                }
            }
        }
        return fallbackYear ?: LocalDate.now().year
    }

    private fun parseMemberCell(raw: String): Pair<String, String> {
        val clean = raw.replace("\n", " ").replace("\r", " ").trim()
        if (clean.isBlank()) return Pair("", "")
        val upper = clean.uppercase(Locale.ROOT)
        if (upper == "TOTAL" || upper == "JUMLA" || upper == "JINA" || upper == "ADA") return Pair("", "")

        var i = 0
        while (i < clean.length && clean[i].isDigit()) {
            i++
        }
        if (i > 0) {
            val num = clean.substring(0, i)
            var rest = clean.substring(i)
            while (rest.startsWith(".") || rest.startsWith(" ") || rest.startsWith("-")) {
                rest = rest.substring(1).trim()
            }
            if (rest.isNotBlank()) {
                return Pair(num, rest)
            }
        }
        return Pair("", clean)
    }

    private fun cleanStr(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.endsWith(".0") && trimmed.substringBefore(".0").all { it.isDigit() }) {
            trimmed.substringBefore(".0")
        } else trimmed
    }

    private fun parseDate(value: String): LocalDate? = try {
        val trimmed = value.trim()
        if (trimmed.isBlank()) null
        else {
            val num = trimmed.toDoubleOrNull()
            if (num != null && num > 30000 && num < 100000) {
                val days = num.toLong()
                val offset = if (days > 60) 0L else 1L
                LocalDate.of(1899, 12, 30).plusDays(days - offset)
            } else {
                val s = trimmed.take(10)
                try { LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE) }
                catch (_: Exception) {
                    try { LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/yyyy")) }
                    catch (_: Exception) {
                        try { LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy/MM/dd")) }
                        catch (_: Exception) {
                            try { LocalDate.parse(s, DateTimeFormatter.ofPattern("dd-MM-yyyy")) }
                            catch (_: Exception) { null }
                        }
                    }
                }
            }
        }
    } catch (_: Exception) { null }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun parseInputSheets(input: InputStream): List<List<List<String>>> {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) throw IllegalArgumentException("The selected file is empty.")

        val isZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        val isOle2Xls = bytes.size >= 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() && bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

        if (isOle2Xls) {
            throw IllegalArgumentException("The file is in legacy binary Excel (.xls) format. Please save or export the file as .xlsx or .csv and try importing again.")
        }

        return if (isZip) {
            parseXlsxSheets(bytes)
        } else {
            val text = ByteArrayInputStream(bytes).bufferedReader(Charsets.UTF_8).readText()
            listOf(parseCsv(text))
        }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val sampleLines = text.lines().take(10).filter { it.isNotBlank() }
        val commaCount = sampleLines.sumOf { line -> line.count { it == ',' } }
        val semiCount = sampleLines.sumOf { line -> line.count { it == ';' } }
        val tabCount = sampleLines.sumOf { line -> line.count { it == '\t' } }

        val delimiter = when {
            semiCount > commaCount && semiCount > tabCount -> ';'
            tabCount > commaCount && tabCount > semiCount -> '\t'
            else -> ','
        }

        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { row.add(cell.toString()); cell.setLength(0) }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) result.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); if (row.any { it.isNotBlank() }) result.add(row) }
        if (quoted) throw IllegalArgumentException("CSV has an unclosed quoted field.")
        return result
    }

    private fun parseXlsxSheets(bytes: ByteArray): List<List<List<String>>> {
        var sharedStrings = emptyList<String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.lowercase(Locale.ROOT).endsWith("xl/sharedstrings.xml")) {
                        val entryBytes = zip.readBytes()
                        sharedStrings = parseSharedStrings(ByteArrayInputStream(entryBytes))
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val sheets = mutableListOf<List<List<String>>>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase(Locale.ROOT)
                    if (name.contains("xl/worksheets/sheet") && name.endsWith(".xml")) {
                        val entryBytes = zip.readBytes()
                        val rows = parseSheet(ByteArrayInputStream(entryBytes), sharedStrings)
                        if (rows.isNotEmpty()) {
                            sheets.add(rows)
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sheets
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val strings = mutableListOf<String>()
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = false
        val doc = dbf.newDocumentBuilder().parse(input)
        val siList = doc.getElementsByTagName("si")
        for (i in 0 until siList.length) {
            val si = siList.item(i) as Element
            val tList = si.getElementsByTagName("t")
            val sb = StringBuilder()
            for (j in 0 until tList.length) {
                sb.append(tList.item(j).textContent)
            }
            strings.add(sb.toString())
        }
        return strings
    }

    private fun parseSheet(input: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = false
        val doc = dbf.newDocumentBuilder().parse(input)
        val rowList = doc.getElementsByTagName("row")
        for (i in 0 until rowList.length) {
            val rowElem = rowList.item(i) as Element
            val cList = rowElem.getElementsByTagName("c")
            val currentRow = mutableMapOf<Int, String>()
            var autoCol = 0
            for (j in 0 until cList.length) {
                val cElem = cList.item(j) as Element
                val ref = cElem.getAttribute("r")
                val type = cElem.getAttribute("t")
                val vNodes = cElem.getElementsByTagName("v")
                val tNodes = cElem.getElementsByTagName("t")
                val rawVal = if (vNodes.length > 0) vNodes.item(0).textContent else if (tNodes.length > 0) tNodes.item(0).textContent else ""
                val cellVal = when (type) {
                    "s" -> {
                        val idx = rawVal.toIntOrNull()
                        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else rawVal
                    }
                    "b" -> if (rawVal == "1") "TRUE" else "FALSE"
                    else -> rawVal
                }
                val colIndex = getColIndexFromRef(ref).let { if (it >= 0) it else autoCol }
                autoCol = colIndex + 1
                currentRow[colIndex] = cellVal
            }
            if (currentRow.isNotEmpty()) {
                val maxCol = currentRow.keys.maxOrNull() ?: -1
                if (maxCol >= 0) {
                    val list = List(maxCol + 1) { col -> currentRow[col] ?: "" }
                    if (list.any { it.isNotBlank() }) {
                        rows.add(list)
                    }
                }
            }
        }
        return rows
    }

    private fun getColIndexFromRef(ref: String): Int {
        if (ref.isBlank()) return -1
        var colStr = ""
        for (ch in ref) {
            if (ch.isLetter()) colStr += ch else break
        }
        if (colStr.isBlank()) return -1
        var index = 0
        for (ch in colStr.uppercase(Locale.ROOT)) {
            index = index * 26 + (ch - 'A' + 1)
        }
        return index - 1
    }
}
