package dev.budgetly.core.imports.tabular

/**
 * Exports fictifs écrits par le code, dans les formes que prennent les exports des banques : données inventées
 * (commerçants « EXEMPLE », numéros de compte factices).
 */
object TabularFixtures {

    /**
     * Style « débit / crédit » : préambule, séparateur « ; », virgule décimale, du plus récent au plus ancien,
     * encodé en Windows-1252, sans colonne de solde, guillemets doublés dans un libellé (écrits « '' » ici). Solde avant : 1 000,00 ; après : 1 000,00 - 54,30 - 12,00 + 1 500,00 - 700,00 = 1 733,70.
     */
    const val debitCreditOpening = 100_000L
    const val debitCreditClosing = 173_370L
    val debitCredit: ByteArray = """
        Compte courant EXEMPLE;;;
        Export du 31/10/2026;;;
        Date;Libellé;Débit euros;Crédit euros
        28/10/2026;"PRLV LOYER EXEMPLE";700,00;
        15/10/2026;VIR SALAIRE SOCIETE EXEMPLE;;1 500,00
        03/10/2026;CB BOULANGERIE EXEMPLE 02/10;12,00;
        01/10/2026;"CB EPICERIE ''CHEZ EXEMPLE''";54,30;
    """.trimIndent().replace("''", "\"\"").replace("\n", "\r\n").toByteArray(charset("windows-1252"))

    /**
     * Style « montant signé + solde » : séparateur « , », guillemets, point décimal, ordre chronologique, deux
     * opérations le même jour listées dans l'ordre inverse de la chaîne des soldes.
     */
    const val signedOpening = 25_000L
    const val signedClosing = 23_650L
    val signedWithBalance: ByteArray = """
        "Date operation","Date valeur","Libelle","Montant","Solde"
        "2026-09-02","2026-09-02","CARTE LIBRAIRIE EXEMPLE","-15.50","234.50"
        "2026-09-05","2026-09-05","CARTE CINEMA EXEMPLE","-9.00","245.50"
        "2026-09-05","2026-09-05","VIR REMBOURSEMENT EXEMPLE","20.00","254.50"
        "2026-09-07","2026-09-08","PRLV ABONNEMENT EXEMPLE","-25.35","220.15"
        "2026-09-09","2026-09-09","VIR DE EXEMPLE","16.35","236.50"
    """.trimIndent().toByteArray()

    /** Même fichier, solde faux le 07/09 : le contrôle doit l'arrêter. */
    val signedWithWrongBalance: ByteArray = String(signedWithBalance).replace("\"220.15\"", "\"230.15\"").toByteArray()

    /** OFX 1.x (SGML, balises non fermées), virgule décimale comme chez certaines banques françaises. */
    const val ofxAccountId = "9999900001000123456789"
    const val ofxClosing = 48_210L
    val ofxSgml: ByteArray = """
        OFXHEADER:100
        DATA:OFXSGML
        VERSION:102
        CHARSET:1252

        <OFX>
        <BANKMSGSRSV1><STMTTRNRS><STMTRS>
        <CURDEF>EUR
        <BANKACCTFROM>
        <BANKID>99999
        <BRANCHID>00001
        <ACCTID>000123456789
        <ACCTTYPE>CHECKING
        </BANKACCTFROM>
        <BANKTRANLIST>
        <DTSTART>20260801
        <DTEND>20260831
        <STMTTRN>
        <TRNTYPE>DEBIT
        <DTPOSTED>20260803
        <TRNAMT>-42,90
        <FITID>A1
        <NAME>CB SUPERMARCHE EXEMPLE
        <MEMO>CARTE 0000 02/08
        <STMTTRN>
        <TRNTYPE>CREDIT
        <DTPOSTED>20260810120000
        <TRNAMT>300,00
        <FITID>A2
        <NAME>VIR EPARGNE EXEMPLE
        </BANKTRANLIST>
        <LEDGERBAL>
        <BALAMT>482,10
        <DTASOF>20260831
        </LEDGERBAL>
        </STMTRS></STMTTRNRS></BANKMSGSRSV1>
        </OFX>
    """.trimIndent().toByteArray(charset("windows-1252"))
    /** Solde avant les opérations OFX : 482,10 - 300,00 + 42,90. */
    const val ofxOpening = 22_500L

    /** OFX 2.x (XML), point décimal, échappements. */
    val ofxXml: ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <?OFX OFXHEADER="200" VERSION="220"?>
        <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>
        <BANKACCTFROM><BANKID>99999</BANKID><ACCTID>LIVRET42</ACCTID></BANKACCTFROM>
        <BANKTRANLIST>
        <STMTTRN><TRNTYPE>INT</TRNTYPE><DTPOSTED>20261231000000.000[+1:CET]</DTPOSTED><TRNAMT>12.34</TRNAMT><NAME>INTERETS 2026 &amp; PRIMES</NAME></STMTTRN>
        </BANKTRANLIST>
        <LEDGERBAL><BALAMT>1012.34</BALAMT><DTASOF>20261231</DTASOF></LEDGERBAL>
        </STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
    """.trimIndent().toByteArray()
}
