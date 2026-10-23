import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type Account, type AccountType, type CsvMapping, type ImportPreview, type TabularAnalysis } from "./api";
import { amount, longDate } from "./format";

/** Fichiers pris par ce parcours plutôt que par l'analyse des relevés PDF. */
export const isTabularFile = (file: File) => /\.(csv|ofx|qfx|txt)$/i.test(file.name) || file.type === "text/csv";

type Role = "none" | "date" | "operationDate" | "label" | "detail" | "amount" | "debit" | "credit" | "balance";

const roleLabel: Record<Role, string> = {
  none: "—",
  date: "Date",
  operationDate: "Date d'opération",
  label: "Libellé",
  detail: "Détail",
  amount: "Montant (signé)",
  debit: "Débit",
  credit: "Crédit",
  balance: "Solde",
};

const accountTypes: { value: AccountType; label: string }[] = [
  { value: "CHECKING", label: "Compte courant" },
  { value: "LIVRET_A", label: "Livret A" },
  { value: "LDDS", label: "LDDS" },
  { value: "SAVINGS", label: "Autre épargne" },
];
const transactionTypes = new Set<AccountType>(accountTypes.map((t) => t.value));

const dateFormats = ["dd/MM/yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd-MM-yyyy", "dd.MM.yyyy", "yyyyMMdd"];

const emptyMapping: CsvMapping = {
  headerRow: null,
  firstDataRow: 0,
  dateColumn: -1,
  operationDateColumn: null,
  labelColumn: -1,
  detailColumns: [],
  amountColumn: null,
  debitColumn: null,
  creditColumn: null,
  balanceColumn: null,
  dateFormat: "dd/MM/yyyy",
  decimalComma: true,
};

function roleOf(m: CsvMapping, column: number): Role {
  if (m.dateColumn === column) return "date";
  if (m.operationDateColumn === column) return "operationDate";
  if (m.labelColumn === column) return "label";
  if (m.amountColumn === column) return "amount";
  if (m.debitColumn === column) return "debit";
  if (m.creditColumn === column) return "credit";
  if (m.balanceColumn === column) return "balance";
  if (m.detailColumns.includes(column)) return "detail";
  return "none";
}

function withRole(m: CsvMapping, column: number, role: Role): CsvMapping {
  const free = (value: number | null) => (value === column ? null : value);
  const next: CsvMapping = {
    ...m,
    dateColumn: m.dateColumn === column ? -1 : m.dateColumn,
    labelColumn: m.labelColumn === column ? -1 : m.labelColumn,
    operationDateColumn: free(m.operationDateColumn),
    amountColumn: free(m.amountColumn),
    debitColumn: free(m.debitColumn),
    creditColumn: free(m.creditColumn),
    balanceColumn: free(m.balanceColumn),
    detailColumns: m.detailColumns.filter((c) => c !== column),
  };
  switch (role) {
    case "date":
      return { ...next, dateColumn: column };
    case "operationDate":
      return { ...next, operationDateColumn: column };
    case "label":
      return { ...next, labelColumn: column };
    case "detail":
      return { ...next, detailColumns: [...next.detailColumns, column].sort((a, b) => a - b) };
    case "amount":
      return { ...next, amountColumn: column, debitColumn: null, creditColumn: null };
    case "debit":
      return { ...next, debitColumn: column, amountColumn: null };
    case "credit":
      return { ...next, creditColumn: column, amountColumn: null };
    case "balance":
      return { ...next, balanceColumn: column };
    default:
      return next;
  }
}

/** « -1 234,56 » saisi par l'utilisateur → centimes, sans passer par un flottant. */
export function parseSignedEuros(text: string): number | null {
  const cleaned = text.replace(/[\s  €]/g, "").replace(",", ".");
  const match = /^([+-]?)(\d+)(?:\.(\d{1,2}))?$/.exec(cleaned);
  if (!match) return null;
  const cents = Number(match[2]) * 100 + Number((match[3] ?? "").padEnd(2, "0"));
  return match[1] === "-" ? -cents : cents;
}

type Props = {
  fileName: string;
  analysis: TabularAnalysis;
  onPreview: (preview: ImportPreview) => void;
  onCancel: () => void;
};

export function TabularImportPanel({ fileName, analysis, onPreview, onCancel }: Props) {
  const isOfx = analysis.format === "OFX";
  const [mapping, setMapping] = useState<CsvMapping>(analysis.suggestedMapping ?? emptyMapping);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountId, setAccountId] = useState<string>(analysis.suggestedAccountId ? String(analysis.suggestedAccountId) : "new");
  const [name, setName] = useState("");
  const [type, setType] = useState<AccountType>("CHECKING");
  const [institution, setInstitution] = useState("");
  const [opening, setOpening] = useState("");
  const [closing, setClosing] = useState("");
  const [busy, setBusy] = useState(false);
  const [issues, setIssues] = useState<string[]>([]);
  const [hint, setHint] = useState<string | null>(null);

  useEffect(() => {
    api
      .accounts()
      .then((list) => {
        const usable = list.filter((a) => transactionTypes.has(a.type));
        setAccounts(usable);
        if (!analysis.suggestedAccountId && usable.length > 0 && !isOfx) setAccountId(String(usable[0].id));
      })
      .catch(() => undefined);
  }, [analysis.suggestedAccountId, isOfx]);

  const needsOpening = isOfx || mapping.balanceColumn === null;
  const needsClosing = isOfx ? analysis.ofx?.closingCents == null : mapping.balanceColumn === null;
  const mappingOk =
    isOfx ||
    (mapping.dateColumn >= 0 && mapping.labelColumn >= 0 && (mapping.amountColumn !== null || (mapping.debitColumn !== null && mapping.creditColumn !== null)));
  const openingCents = parseSignedEuros(opening);
  const closingCents = parseSignedEuros(closing);
  const accountOk = accountId !== "new" || name.trim().length > 0;
  const balancesOk = (!needsOpening || openingCents !== null) && (!needsClosing || closingCents !== null);
  const canSubmit = !busy && mappingOk && accountOk && balancesOk && analysis.token !== null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!analysis.token) return;
    setBusy(true);
    setIssues([]);
    setHint(null);
    try {
      const preview = await api.previewTabular(analysis.token, {
        mapping: isOfx ? undefined : mapping,
        account:
          accountId === "new"
            ? { name: name.trim(), type, institution: institution.trim() || undefined }
            : { existingAccountId: Number(accountId) },
        openingCents: needsOpening ? openingCents ?? undefined : undefined,
        closingCents: needsClosing ? closingCents ?? undefined : undefined,
      });
      if (preview.status === "READY") {
        onPreview(preview);
        return;
      }
      setIssues(preview.issues);
      const account = preview.accounts[0];
      if (account && !account.balanceOk) {
        setHint(`D'après les opérations du fichier et le solde de départ saisi, le solde final serait ${amount(account.expectedClosingCents)}.`);
      }
    } catch (e) {
      setIssues([e instanceof ApiError ? e.message : "Le fichier n'a pas pu être lu."]);
    } finally {
      setBusy(false);
    }
  }

  const columns = Array.from({ length: analysis.columnCount }, (_, i) => i);

  return (
    <form className="preview tabular" aria-label="Import d'un export CSV ou OFX" onSubmit={submit}>
      <p className="preview-intro">
        {fileName} · {isOfx ? "OFX" : `CSV, ${analysis.encoding}, séparateur « ${analysis.delimiter === "\t" ? "tabulation" : analysis.delimiter} »`}
        {isOfx && analysis.ofx && (
          <>
            {" "}
            · {analysis.ofx.transactionCount} opérations
            {analysis.ofx.firstDate && analysis.ofx.lastDate && ` du ${longDate(analysis.ofx.firstDate)} au ${longDate(analysis.ofx.lastDate)}`}
          </>
        )}
      </p>

      {!isOfx && (
        <section className="settings-block" aria-labelledby="tabular-columns">
          <h2 id="tabular-columns">Colonnes</h2>
          <p className="muted">
            {analysis.mappingRemembered
              ? "Correspondance reprise de votre dernier import avec les mêmes en-têtes."
              : "Correspondance proposée d'après les en-têtes : vérifiez-la sur les premières lignes."}
          </p>
          <div className="form-row">
            <label className="field field--inline">
              <span>Ligne d'en-tête</span>
              <select
                value={mapping.headerRow ?? -1}
                onChange={(e) => {
                  const header = Number(e.target.value);
                  setMapping({ ...mapping, headerRow: header < 0 ? null : header, firstDataRow: header + 1 });
                }}
              >
                <option value={-1}>Aucune</option>
                {analysis.rows.map((_, i) => (
                  <option key={i} value={i}>
                    Ligne {i + 1}
                  </option>
                ))}
              </select>
            </label>
            <label className="field field--inline">
              <span>Format des dates</span>
              <select value={mapping.dateFormat} onChange={(e) => setMapping({ ...mapping, dateFormat: e.target.value })}>
                {dateFormats.map((f) => (
                  <option key={f} value={f}>
                    {f.replace(/d/g, "j").replace(/M/g, "m").replace(/y/g, "a")}
                  </option>
                ))}
              </select>
            </label>
            <label className="field field--inline">
              <span>Décimales</span>
              <select value={mapping.decimalComma ? "comma" : "dot"} onChange={(e) => setMapping({ ...mapping, decimalComma: e.target.value === "comma" })}>
                <option value="comma">Virgule (1 234,56)</option>
                <option value="dot">Point (1234.56)</option>
              </select>
            </label>
          </div>
          <div className="table-scroll">
            <table className="preview-transactions tabular-sample">
              <thead>
                <tr>
                  {columns.map((c) => (
                    <th key={c}>
                      <select
                        aria-label={`Rôle de la colonne ${c + 1}`}
                        value={roleOf(mapping, c)}
                        onChange={(e) => setMapping(withRole(mapping, c, e.target.value as Role))}
                      >
                        {(Object.keys(roleLabel) as Role[]).map((r) => (
                          <option key={r} value={r}>
                            {roleLabel[r]}
                          </option>
                        ))}
                      </select>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {analysis.rows.map((row, i) => (
                  <tr key={i} className={i < mapping.firstDataRow ? "row--muted" : ""}>
                    {columns.map((c) => (
                      <td key={c} className={i === mapping.headerRow ? "tabular-header" : ""}>
                        {row[c] ?? ""}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted">
            {analysis.rowCount} lignes dans le fichier{analysis.rowCount > analysis.rows.length && `, ${analysis.rows.length} premières affichées`}.
          </p>
        </section>
      )}

      <section className="settings-block" aria-labelledby="tabular-account">
        <h2 id="tabular-account">Compte</h2>
        <div className="form-row">
          <label className="field field--inline">
            <span>Importer dans</span>
            <select value={accountId} onChange={(e) => setAccountId(e.target.value)}>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} · {a.institution} · {a.ibanMasked}
                </option>
              ))}
              <option value="new">Nouveau compte…</option>
            </select>
          </label>
          {accountId === "new" && (
            <>
              <label className="field field--inline">
                <span>Nom</span>
                <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Compte courant" />
              </label>
              <label className="field field--inline">
                <span>Type</span>
                <select value={type} onChange={(e) => setType(e.target.value as AccountType)}>
                  {accountTypes.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field--inline">
                <span>Banque</span>
                <input value={institution} onChange={(e) => setInstitution(e.target.value)} placeholder="Ma banque" />
              </label>
            </>
          )}
        </div>
      </section>

      {(needsOpening || needsClosing) && (
        <section className="settings-block" aria-labelledby="tabular-balances">
          <h2 id="tabular-balances">Soldes de contrôle</h2>
          <p className="muted">
            {needsOpening && needsClosing
              ? "Le fichier ne donne pas les soldes : saisissez-les tels qu'affichés par votre banque."
              : "Le fichier ne donne pas le solde de départ : saisissez-le tel qu'affiché par votre banque."}{" "}
            L'import n'est accepté que si les opérations du fichier passent exactement d'un solde à l'autre.
          </p>
          <div className="form-row">
            {needsOpening && (
              <label className="field field--inline">
                <span>Solde avant la première opération</span>
                <input inputMode="decimal" value={opening} onChange={(e) => setOpening(e.target.value)} placeholder="1 234,56" />
              </label>
            )}
            {needsClosing && (
              <label className="field field--inline">
                <span>Solde après la dernière opération</span>
                <input inputMode="decimal" value={closing} onChange={(e) => setClosing(e.target.value)} placeholder="1 234,56" />
              </label>
            )}
            {isOfx && !needsClosing && analysis.ofx?.closingCents != null && (
              <p className="muted">
                Solde de fin lu dans le fichier : {amount(analysis.ofx.closingCents)}
                {analysis.ofx.closingDate && ` au ${longDate(analysis.ofx.closingDate)}`}.
              </p>
            )}
          </div>
        </section>
      )}

      {issues.map((issue) => (
        <p key={issue} className="form-error" role="alert">
          {issue}
        </p>
      ))}
      {hint && <p className="muted">{hint}</p>}

      <div className="preview-actions">
        <button type="submit" className="primary" disabled={!canSubmit}>
          {busy ? "Lecture…" : "Voir l'aperçu"}
        </button>
        <button type="button" onClick={onCancel}>
          Annuler
        </button>
      </div>
    </form>
  );
}
