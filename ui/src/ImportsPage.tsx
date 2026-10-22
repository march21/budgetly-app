import { useEffect, useRef, useState, type DragEvent } from "react";
import { api, ApiError, type ImportPreview, type ImportRecord, type TabularAnalysis } from "./api";
import { isTabularFile, TabularImportPanel } from "./TabularImport";
import { Amount } from "./Amount";
import { kindLabel, longDate, noIsinLabel, shortDate, timestamp } from "./format";

type Props = { onImported: () => void; onCancelled: () => void; onShowTransactions: () => void };

type Step =
  | { kind: "idle" }
  | { kind: "reading" }
  | { kind: "preview"; preview: ImportPreview }
  | { kind: "saving"; preview: ImportPreview }
  | { kind: "done"; record: ImportRecord }
  | { kind: "batch"; items: BatchItem[]; running: boolean }
  | { kind: "tabular"; fileName: string; analysis: TabularAnalysis }
  | { kind: "error"; message: string };

/** Un fichier d'un dépôt multiple : aperçu, puis résultat de la validation. */
type BatchItem = {
  name: string;
  preview?: ImportPreview;
  error?: string;
  record?: ImportRecord;
  failure?: string;
};

const batchReady = (item: BatchItem) => item.preview?.status === "READY" && !item.record && !item.failure;

export function ImportsPage({ onImported, onCancelled, onShowTransactions }: Props) {
  const [step, setStep] = useState<Step>({ kind: "idle" });
  const [history, setHistory] = useState<ImportRecord[]>([]);
  const [dragging, setDragging] = useState(false);
  const [confirming, setConfirming] = useState<number | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const loadHistory = () => {
    api.imports().then(setHistory).catch(() => undefined);
  };
  useEffect(loadHistory, []);

  async function readAll(list: FileList | null | undefined) {
    const all = [...(list ?? [])];
    const tabular = all.filter(isTabularFile);
    const files = all.filter((f) => f.name.toLowerCase().endsWith(".pdf") || f.type === "application/pdf");
    if (files.length === 0 && tabular.length > 0) {
      if (tabular.length > 1) {
        setStep({ kind: "error", message: "Déposez les exports CSV ou OFX un par un : chacun demande de choisir son compte." });
        return;
      }
      return readTabular(tabular[0]);
    }
    if (files.length <= 1) return read(files[0] ?? list?.[0]);
    setStep({ kind: "reading" });
    const items: BatchItem[] = [];
    // Un aperçu à la fois : le cœur analyse un PDF par requête.
    for (const file of files) {
      try {
        items.push({ name: file.name, preview: await api.preview(file) });
      } catch (e) {
        items.push({ name: file.name, error: e instanceof ApiError ? e.message : "Illisible." });
      }
    }
    if (input.current) input.current.value = "";
    setStep({ kind: "batch", items, running: false });
  }

  /** Validation dans l'ordre chronologique : doublons et virements internes se recoupent comme un import à la fois. */
  async function commitBatch(items: BatchItem[]) {
    const order = items
      .map((item, index) => ({ item, index }))
      .filter(({ item }) => batchReady(item))
      .sort((a, b) => (a.item.preview!.periodStart ?? "").localeCompare(b.item.preview!.periodStart ?? ""));
    const next = [...items];
    setStep({ kind: "batch", items: next, running: true });
    for (const { item, index } of order) {
      try {
        next[index] = { ...item, record: await api.commit(item.preview!.previewId!) };
      } catch (e) {
        next[index] = { ...item, failure: e instanceof ApiError ? e.message : "L'import a échoué." };
      }
      setStep({ kind: "batch", items: [...next], running: true });
    }
    setStep({ kind: "batch", items: [...next], running: false });
    onImported();
    loadHistory();
  }

  async function readTabular(file: File) {
    setStep({ kind: "reading" });
    try {
      const analysis = await api.analyzeTabular(file);
      if (analysis.alreadyImported) {
        setStep({ kind: "error", message: "Ce fichier a déjà été importé." });
      } else {
        setStep({ kind: "tabular", fileName: file.name, analysis });
      }
    } catch (e) {
      setStep({ kind: "error", message: e instanceof ApiError ? e.message : "Le fichier n'a pas pu être lu." });
    } finally {
      if (input.current) input.current.value = "";
    }
  }

  async function read(file: File | undefined) {
    if (!file) return;
    setStep({ kind: "reading" });
    try {
      setStep({ kind: "preview", preview: await api.preview(file) });
    } catch (e) {
      setStep({ kind: "error", message: e instanceof ApiError ? e.message : "Le relevé n'a pas pu être lu." });
    } finally {
      if (input.current) input.current.value = "";
    }
  }

  async function commit(preview: ImportPreview) {
    if (!preview.previewId) return;
    setStep({ kind: "saving", preview });
    try {
      const record = await api.commit(preview.previewId);
      setStep({ kind: "done", record });
      onImported();
      loadHistory();
    } catch (e) {
      setStep({ kind: "error", message: e instanceof ApiError ? e.message : "L'import a échoué." });
    }
  }

  async function cancel(id: number) {
    setConfirming(null);
    setHistoryError(null);
    try {
      await api.cancelImport(id);
      loadHistory();
      onCancelled();
    } catch (e) {
      setHistoryError(e instanceof ApiError ? e.message : "L'import n'a pas été annulé.");
    }
  }

  function drop(event: DragEvent) {
    event.preventDefault();
    setDragging(false);
    readAll(event.dataTransfer.files);
  }

  const showDropzone = step.kind === "idle" || step.kind === "error" || step.kind === "done";

  return (
    <section className="page">
      <header className="page-header">
        <h1>Importer un relevé</h1>
      </header>

      {step.kind === "done" && (
        <div className="notice notice--ok" role="status">
          {step.record.impact.positionsRecorded > 0 ? (
            <p>Relevé importé : {step.record.impact.positionsRecorded} supports enregistrés.</p>
          ) : (
            <p>
              Relevé importé : {step.record.impact.transactionsCreated} opération
              {step.record.impact.transactionsCreated > 1 ? "s" : ""} ajoutée
              {step.record.impact.transactionsCreated > 1 ? "s" : ""}
              {step.record.impact.duplicatesSkipped > 0 && `, ${step.record.impact.duplicatesSkipped} déjà connue(s) ignorée(s)`}.
            </p>
          )}
          <button className="link" onClick={onShowTransactions}>
            Voir les opérations
          </button>
        </div>
      )}
      {step.kind === "error" && (
        <div className="notice notice--error" role="alert">
          <p>{step.message}</p>
        </div>
      )}

      {showDropzone && (
        <label
          className={`dropzone ${dragging ? "dropzone--over" : ""}`}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={drop}
        >
          <input
            ref={input}
            type="file"
            accept="application/pdf,.pdf,.csv,text/csv,.ofx,.qfx"
            multiple
            className="visually-hidden"
            onChange={(e) => readAll(e.target.files)}
          />
          <span className="dropzone-title">Déposez un ou plusieurs relevés PDF, ou un export CSV / OFX, ici ou cliquez pour les choisir</span>
          <span className="muted">
            Relevés de comptes La Banque Postale, évaluation de PEA EasyBourse, bulletin annuel EasyVie, avis d'opération Natixis (PEE). Pour les
            autres banques : l'export CSV ou OFX de vos opérations. Le fichier est lu sur cet ordinateur puis oublié : seule son empreinte est
            conservée.
          </span>
        </label>
      )}

      {step.kind === "reading" && <p className="muted">Lecture des relevés…</p>}

      {step.kind === "batch" && (
        <BatchPanel
          items={step.items}
          running={step.running}
          onCommit={() => commitBatch(step.items)}
          onClose={() => setStep({ kind: "idle" })}
          onShowTransactions={onShowTransactions}
        />
      )}

      {step.kind === "tabular" && (
        <TabularImportPanel
          fileName={step.fileName}
          analysis={step.analysis}
          onPreview={(preview) => setStep({ kind: "preview", preview })}
          onCancel={() => setStep({ kind: "idle" })}
        />
      )}

      {(step.kind === "preview" || step.kind === "saving") && (
        <PreviewPanel
          preview={step.preview}
          saving={step.kind === "saving"}
          onCommit={() => commit(step.preview)}
          onCancel={() => setStep({ kind: "idle" })}
        />
      )}

      {history.length > 0 && (
        <section className="history">
          <h2>Relevés importés</h2>
          <p className="muted">
            Le dernier relevé importé pour un compte peut être annulé : ses opérations, soldes et positions sont retirés,
            et le fichier pourra être importé à nouveau.
          </p>
          {historyError && (
            <p className="form-error" role="alert">
              {historyError}
            </p>
          )}
          <table>
            <thead>
              <tr>
                <th>Importé le</th>
                <th>Période</th>
                <th className="num">Ajouts</th>
                <th className="num">Doublons ignorés</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {history.map((record) => (
                <tr key={record.id} className={record.cancelledAt ? "row--muted" : ""}>
                  <td>{timestamp(record.importedAt)}</td>
                  <td>
                    {record.periodStart === record.periodEnd
                      ? `Au ${longDate(record.periodEnd)}`
                      : `${shortDate(record.periodStart)} – ${longDate(record.periodEnd)}`}
                  </td>
                  <td className="num">
                    {record.impact.positionsRecorded > 0
                      ? `${record.impact.positionsRecorded} supports`
                      : `${record.impact.transactionsCreated} opérations`}
                  </td>
                  <td className="num">{record.impact.duplicatesSkipped}</td>
                  <td className="row-actions">
                    {record.cancelledAt ? (
                      <span className="muted">Annulé le {timestamp(record.cancelledAt)}</span>
                    ) : record.cancellable ? (
                      confirming === record.id ? (
                        <>
                          <button className="link link--danger" onClick={() => cancel(record.id)}>
                            Confirmer l'annulation
                          </button>
                          <button className="link" onClick={() => setConfirming(null)}>
                            Garder
                          </button>
                        </>
                      ) : (
                        <button className="link" onClick={() => setConfirming(record.id)}>
                          Annuler l'import
                        </button>
                      )
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </section>
  );
}

function PreviewPanel({
  preview,
  saving,
  onCommit,
  onCancel,
}: {
  preview: ImportPreview;
  saving: boolean;
  onCommit: () => void;
  onCancel: () => void;
}) {
  if (preview.status === "ALREADY_IMPORTED") {
    return (
      <div className="notice">
        <p>Ce relevé a déjà été importé. Rien n'a été modifié.</p>
        <button className="link" onClick={onCancel}>
          Choisir un autre relevé
        </button>
      </div>
    );
  }

  if (preview.documentType === "PORTFOLIO" && preview.portfolio) {
    return <PortfolioPreviewPanel preview={preview} saving={saving} onCommit={onCommit} onCancel={onCancel} />;
  }

  const toImport = preview.accounts.reduce((sum, a) => sum + a.newTransactionCount, 0);
  const duplicates = preview.accounts.reduce((sum, a) => sum + a.duplicateCount, 0);
  const ready = preview.status === "READY";

  return (
    <section className="preview" aria-label="Aperçu du relevé">
      <p className="preview-intro">
        {preview.institution}, du {preview.periodStart && longDate(preview.periodStart)} au{" "}
        {preview.periodEnd && longDate(preview.periodEnd)}.
      </p>

      {preview.issues.map((issue) => (
        <div key={issue} className="notice notice--error" role="alert">
          <p>{issue}</p>
        </div>
      ))}

      <table className="preview-accounts">
        <thead>
          <tr>
            <th>Compte</th>
            <th className="num">Ancien solde</th>
            <th className="num">Nouveau solde</th>
            <th>Contrôle</th>
            <th className="num">Nouvelles</th>
            <th className="num">Déjà connues</th>
          </tr>
        </thead>
        <tbody>
          {preview.accounts.map((account) => (
            <tr key={account.key}>
              <td>
                <span className="cell-title">{account.name}</span>
                <span className="cell-meta">
                  {account.ibanMasked}
                  {account.newAccount && " · nouveau compte"}
                </span>
              </td>
              <td className="num">
                <Amount cents={account.openingCents} />
              </td>
              <td className="num">
                <Amount cents={account.closingCents} />
              </td>
              <td>
                {account.balanceOk ? (
                  <span className="check check--ok">Solde vérifié</span>
                ) : (
                  <span className="check check--ko">
                    Écart de <Amount cents={account.closingCents - account.expectedClosingCents} />
                  </span>
                )}
              </td>
              <td className="num">{account.newTransactionCount}</td>
              <td className="num">{account.duplicateCount}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="preview-actions">
        <button className="primary" onClick={onCommit} disabled={!ready || saving || toImport === 0}>
          {saving
            ? "Import en cours…"
            : toImport === 0
              ? "Rien de nouveau à importer"
              : `Importer ${toImport} opération${toImport > 1 ? "s" : ""}`}
        </button>
        <button className="secondary" onClick={onCancel} disabled={saving}>
          Annuler
        </button>
        {duplicates > 0 && (
          <span className="muted">
            {duplicates} opération{duplicates > 1 ? "s" : ""} déjà importée{duplicates > 1 ? "s" : ""} sera
            {duplicates > 1 ? "ont" : ""} ignorée{duplicates > 1 ? "s" : ""}.
          </span>
        )}
      </div>

      <table className="preview-transactions">
        <thead>
          <tr>
            <th>Date</th>
            <th>Opération</th>
            <th>Compte</th>
            <th className="num">Montant</th>
          </tr>
        </thead>
        <tbody>
          {preview.transactions.map((t, index) => (
            <tr key={index} className={t.duplicate ? "row--muted" : ""}>
              <td>{shortDate(t.operationDate)}</td>
              <td>
                <span className="cell-title">{t.label}</span>
                <span className="cell-meta">
                  {kindLabel[t.kind]}
                  {t.internalTransfer && " · entre mes comptes"}
                  {t.duplicate && " · déjà importée"}
                </span>
              </td>
              <td>{preview.accounts[t.accountKey]?.name}</td>
              <td className="num">
                <Amount cents={t.amountCents} signed />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function PortfolioPreviewPanel({
  preview,
  saving,
  onCommit,
  onCancel,
}: {
  preview: ImportPreview;
  saving: boolean;
  onCommit: () => void;
  onCancel: () => void;
}) {
  const p = preview.portfolio!;
  return (
    <section className="preview" aria-label="Aperçu du relevé de portefeuille">
      <p className="preview-intro">
        {p.name} ({p.numberMasked}
        {p.newAccount && ", nouveau"}), au {longDate(p.valuationDate)}.
      </p>
      {preview.issues.map((issue) => (
        <div key={issue} className="notice notice--error" role="alert">
          <p>{issue}</p>
        </div>
      ))}
      <dl className="envelope-facts">
        <div>
          <dt>Total du relevé</dt>
          <dd>
            <Amount cents={p.printedTotalCents} />
          </dd>
        </div>
        <div>
          <dt>Contrôle</dt>
          <dd>
            {p.balanced ? (
              <span className="check check--ok">Somme des lignes vérifiée</span>
            ) : (
              <span className="check check--ko">
                Écart de <Amount cents={p.printedTotalCents - p.computedTotalCents} />
              </span>
            )}
          </dd>
        </div>
        {p.contributionsCents !== null && (
          <div>
            <dt>Versements cumulés</dt>
            <dd>
              <Amount cents={p.contributionsCents} />
            </dd>
          </div>
        )}
      </dl>
      <div className="preview-actions">
        <button className="primary" onClick={onCommit} disabled={preview.status !== "READY" || saving}>
          {saving ? "Import en cours…" : `Importer ${p.positions.length} supports`}
        </button>
        <button className="secondary" onClick={onCancel} disabled={saving}>
          Annuler
        </button>
      </div>
      <table className="preview-transactions">
        <thead>
          <tr>
            <th>Support</th>
            <th className="num">Parts</th>
            <th className="num">Cours</th>
            <th className="num">Valeur</th>
          </tr>
        </thead>
        <tbody>
          {p.positions.map((position, index) => (
            <tr key={index}>
              <td>
                <span className="cell-title">{position.name}</span>
                <span className="cell-meta">{position.isin ?? noIsinLabel(position.kind)}</span>
              </td>
              <td className="num">{position.quantity?.replace(".", ",") ?? "—"}</td>
              <td className="num">{position.unitPrice ? `${position.unitPrice.replace(".", ",")} €` : "—"}</td>
              <td className="num">
                <Amount cents={position.valueCents} />
              </td>
            </tr>
          ))}
          {p.cashCents > 0 && (
            <tr>
              <td className="cell-title">Liquidités</td>
              <td />
              <td />
              <td className="num">
                <Amount cents={p.cashCents} />
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}

function batchSummary(item: BatchItem): string {
  const p = item.preview;
  if (!p) return "";
  if (p.documentType === "PORTFOLIO" && p.portfolio) {
    return `${p.portfolio.name}, au ${longDate(p.portfolio.valuationDate)} · ${p.portfolio.positions.length} supports`;
  }
  const fresh = p.accounts.reduce((sum, a) => sum + a.newTransactionCount, 0);
  const period = p.periodStart && p.periodEnd ? `${shortDate(p.periodStart)} – ${longDate(p.periodEnd)}` : "";
  if (p.status !== "READY") return `${p.institution ?? ""}, ${period}`;
  return `${p.institution ?? ""}, ${period} · ${p.accounts.length} compte${p.accounts.length > 1 ? "s" : ""}, ${fresh} opération${fresh > 1 ? "s" : ""} nouvelle${fresh > 1 ? "s" : ""}`;
}

function batchStatus(item: BatchItem): { text: string; ok: boolean | null } {
  if (item.record) return { text: "Importé", ok: true };
  if (item.failure) return { text: item.failure, ok: false };
  if (item.error) return { text: item.error, ok: false };
  switch (item.preview?.status) {
    case "READY":
      return { text: "Prêt", ok: null };
    case "ALREADY_IMPORTED":
      return { text: "Déjà importé", ok: null };
    default:
      return { text: item.preview?.issues[0] ?? "Contrôles non passés", ok: false };
  }
}

function BatchPanel({
  items,
  running,
  onCommit,
  onClose,
  onShowTransactions,
}: {
  items: BatchItem[];
  running: boolean;
  onCommit: () => void;
  onClose: () => void;
  onShowTransactions: () => void;
}) {
  const ready = items.filter(batchReady).length;
  const imported = items.filter((i) => i.record).length;
  const finished = !running && imported > 0 && ready === 0;
  return (
    <section className="preview batch" aria-label="Relevés déposés">
      <p className="preview-intro">
        {items.length} relevés déposés
        {finished ? `, ${imported} importé${imported > 1 ? "s" : ""}.` : ` : ${ready} prêt${ready > 1 ? "s" : ""} à importer.`}
      </p>
      <table>
        <thead>
          <tr>
            <th>Fichier</th>
            <th>État</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item, index) => {
            const status = batchStatus(item);
            return (
              <tr key={index} className={item.preview?.status === "ALREADY_IMPORTED" ? "row--muted" : ""}>
                <td>
                  <span className="cell-title">{item.name}</span>
                  <span className="cell-meta">{batchSummary(item)}</span>
                </td>
                <td className={status.ok === true ? "batch-status--ready" : status.ok === false ? "batch-status--ko" : ""}>
                  {status.text}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="preview-actions">
        {finished ? (
          <>
            <button className="primary" onClick={onShowTransactions}>
              Voir les opérations
            </button>
            <button className="secondary" onClick={onClose}>
              Déposer d'autres relevés
            </button>
          </>
        ) : (
          <>
            <button className="primary" onClick={onCommit} disabled={running || ready === 0}>
              {running ? "Import en cours…" : ready === 0 ? "Rien à importer" : `Importer ${ready} relevé${ready > 1 ? "s" : ""}`}
            </button>
            <button className="secondary" onClick={onClose} disabled={running}>
              Annuler
            </button>
            <span className="muted">Les relevés sont importés du plus ancien au plus récent.</span>
          </>
        )}
      </div>
    </section>
  );
}
