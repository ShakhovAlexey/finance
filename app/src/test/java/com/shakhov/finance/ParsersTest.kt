package com.shakhov.finance

import org.junit.Assert.*
import org.junit.Test

class ParsersTest {
    private fun t(s: String) = (Parsers.parse(s) as Parsers.Result.T).p

    @Test fun balance() {
        val r = Parsers.parse("♦️   12-345\nיתרת חשבונך: 10,250.50 ש\"ח, \nהיתרה לא סופית ויתכן שכוללת תנועות על תנאי. למידע נוסף ניתן לצפות בתנועות החשבון בדיגיטל.\nלשירותך, בנק הפועלים,")
        val b = (r as Parsers.Result.B).b
        assertEquals("12-345", b.account); assertEquals(10250.50, b.amount, 0.001)
    }
    @Test fun check() {
        val p = t("♦️ 12-345:\nנפרע שיק בסך\n1,800.00 שח\nשמספרו 55501")
        assertEquals(Types.EXPENSE, p.type); assertEquals(1800.0, p.amount, 0.001)
        assertEquals("чек №55501", p.note); assertEquals("12-345", p.account)
    }
    @Test fun credit() {
        val p = t("♦️ 12-678:\nחשבונך זוכה בסך\n1,100.00 ש\"ח.")
        assertEquals(Types.INCOME, p.type); assertEquals(Parsers.Kind.CREDIT, p.kind); assertEquals(1100.0, p.amount, 0.001)
    }
    @Test fun salary() {
        val p = t("♦️ 12-345:\nבוצע זיכוי משכורת בסך\n9,876.54 שח ")
        assertEquals(Parsers.Kind.SALARY, p.kind); assertEquals(9876.54, p.amount, 0.001)
    }
    @Test fun calOnline() {
        val p = t("היי, ב 19/11 קיבלנו בקשה לעסקת אינטרנט/טלפון בכרטיס ויזה H&O\nהמסתיים ב 1234 בFACEBK *7MD6DF2\nבסך 70.00 שח.\nאפשר לראות מידע נוסף על העסקה באפליקציה\nhttps://www.cal-online.co.il/mobile?link=2018\nתמיד לשירותך,\nכאל")
        assertEquals(70.0, p.amount, 0.001); assertEquals("1234", p.card); assertEquals("FACEBK *7MD6DF2", p.merchant)
        assertEquals(19, p.day); assertEquals(11, p.month)
    }
    @Test fun calCash() {
        val p = t("היי, ב-17/11 בוצעה בכרטיס ויזה H&O המסתיים ב-1234 משיכת מזומן בסך 200 שח.\nאפשר לצפות במידע נוסף באפליקצית כאל")
        assertEquals(Parsers.Kind.CASH, p.kind); assertEquals(200.0, p.amount, 0.001); assertEquals(17, p.day)
    }
    @Test fun calInstallments() {
        val p = t("היי, בכרטיס ויזה H&O המסתיים ב-1234 קיבלנו בקשה לעסקת תשלומים ב-15/11 בKSP נצרת בסך 2579 שח.\nאפשר לראות מידע נוסף באפליקציה של כאל")
        assertEquals("KSP נצרת", p.merchant); assertEquals(2579.0, p.amount, 0.001); assertEquals(15, p.day)
        assertTrue(p.installments != 0)
    }
    @Test fun wallet() {
        val p = Parsers.parseWallet("GOOZA", "₪140.00 with Visa ••1234")!!
        assertEquals(140.0, p.amount, 0.001); assertEquals("1234", p.card); assertEquals("GOOZA", p.merchant)
        val q = Parsers.parseWallet("Лахав", "70,50 ₪ · Visa •••• 1234")!!
        assertEquals(70.5, q.amount, 0.001)
    }
    @Test fun receipt() {
        val r = Parsers.parseReceipt("נקטרינה 19.35\nרימון 10.35\nהנחה -2.00\n7290000123456 ביצים 13.13\nסה\"כ לתשלום 40.83\nאשראי 40.83")
        assertEquals(3, r.items.size); assertEquals(8.35, r.items[1].price, 0.001); assertEquals(40.83, r.total!!, 0.001)
    }

    @Test fun adIsIgnored() {
        val ad = "טסים באל על ועוד לא הצטרפתם ל- FLY CARD?!\nמגיע לך לצבור עד 20% תוספת נקודות ויהלומים עם FLY CARD על טיסות באל על.\nמצטרפים וטסים אל על> https://go.el-al.co.il/1HFKrfbPN\nבתקנון עד 31.12.26. כפוף לרכישות בסך 10,000 ש\"ח עד 31.3.27, לתשלום מיסים. הנפקת הכרטיס בכפוף לשיקול דעתה של ישראכרט בע\"מ.\nיש לשלוח הסר למספר 0539001093 להסרה מרשימת התפוצה"
        assertNull(Parsers.parse(ad))
        // "לשיקול" must not be taken for a check even without ad words
        assertNull(Parsers.parse("הנפקה לשיקול דעת הבנק בסך 500 ש\"ח"))
        // real bank message still works
        assertEquals(Parsers.Kind.CHECK, (Parsers.parse("♦️ 12-345:\nנפרע שיק בסך\n1,800.00 שח\nשמספרו 55501") as Parsers.Result.T).p.kind)
    }

    @Test fun installmentsCount() {
        val p = (Parsers.parse("היי, בכרטיס ויזה H&O המסתיים ב-1234 קיבלנו בקשה לעסקת תשלומים ב-15/11 בKSP נצרת ב-3 תשלומים בסך 900 שח.") as Parsers.Result.T).p
        assertEquals("KSP נצרת", p.merchant); assertEquals(3, p.installments)
    }

    @Test fun calPush() {
        val p = (Parsers.parse("בוצעה עסקה\nב-קשת טעמים נוף הגליל בסך ₪137.8 בכרטיס ויזה 1234, פרטים נוספים בעוד רגע באפליקציה >") as Parsers.Result.T).p
        assertEquals("קשת טעמים נוף הגליל", p.merchant); assertEquals(137.8, p.amount, 0.001); assertEquals("1234", p.card)
        assertTrue(Parsers.sameMerchant("מרכז המזון Merkaza נוף הגליל", "MERKAZA נוף הגליל"))
        assertFalse(Parsers.sameMerchant("FACEBK *D8PPWEWPX4", "סטופ"))
    }
    @Test fun bankPdfText() {
        val r = Importers.pdfText("## ₪10,000.00 200.00 כרטיסי אשראי ל 18/11/2026\n## ₪10,200.00 1,000.00 שיק 10/11/2026\n## ₪11,200.00 5,000.00 משכורת 09/11/2026\n## ₪6,200.00 9.00 עמלה 01/11/2026")
        assertEquals(listOf(Types.TRANSFER, Types.EXPENSE, Types.INCOME, Types.EXPENSE), r.map { it.type })
        assertEquals(Types.EXPENSE, Importers.pdfText("## ₪10,000.00 868.62 מקס 10/11/2026\n## ₪10,868.62 1.00 x 09/11/2026").first().type)
        assertEquals(Types.EXPENSE, (Parsers.parse("♦️ 12-345:\nחשבונך חויב בסך 868.62 ש\"ח לטובת מקס") as Parsers.Result.T).p.type)
        assertEquals(Types.TRANSFER, (Parsers.parse("♦️ 12-345:\nחשבונך חויב בסך 5,488.56 ש\"ח לטובת כאל") as Parsers.Result.T).p.type)
        assertEquals(listOf(200.0, 1000.0, 5000.0, 9.0), r.map { it.amount })
    }
}
