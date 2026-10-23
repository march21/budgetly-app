import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type AssetClass, type Availability, type Envelope, type NetWorth, type PriceReport } from "./api";
import { Amount } from "./Amount";
import { amount, longDate, noIsinLabel, parseEuros, percent, shortDate } from "./format";

const statusLabel: Record<string, string> = {
  FOUND: "Cours trouvé",
  NOT_FOUND: "Inconnu de la source",
  MISMATCH: "Écarté : ne correspond pas au relevé",
  ERROR: "Source injoignable",
  UNAVAILABLE: "Aucune source configurée",
};

const rate = new Intl.NumberFormat("fr-FR", { style: "percent", minimumFractionDigits: 1, maximumFractionDigits: 1, signDisplay: "exceptZero" });
const share = new Intl.NumberFormat("fr-FR", { style: "percent", maximumFractionDigits: 0 });
const monthName = new Intl.DateTimeFormat("fr-FR", { month: "short", year: "numeric" });
const compact = new Intl.NumberFormat("fr-FR", { notation: "compact", maximumFractionDigits: 1 });

const assetClasses: { key: AssetClass; label: string; color: string }[] = [
  { key: "CURRENT_ACCOUNTS", label: "Comptes courants", color: "var(--series-1)" },
  { key: "SAVINGS_ACCOUNTS", label: "Livrets et épargne", color: "var(--series-2)" },
  { key: "ENVELOPE_CASH", label: "Liquidités des enveloppes", color: "var(--series-3)" },
  { key: "MONEY_MARKET", label: "Monétaire", color: "var(--series-4)" },
  { key: "EURO_FUNDS", label: "Fonds en euros", color: "var(--series-5)" },
  { key: "EQUITY_AND_FUNDS", label: "Actions et fonds", color: "var(--series-6)" },
  { key: "UNALLOCATED", label: "Non ventilé (valeur saisie sans détail)", color: "var(--muted)" },
];

const quantity = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 5 });
const unitPrice = new Intl.NumberFormat("fr-FR", { minimumFractionDigits: 2, maximumFractionDigits: 4 });

export function PortfolioPage({ onChanged, onImport }: { onChanged: () => void; onImport: () => void }) {
  const [worth, setWorth] = useState<NetWorth | null>(null);
  const [report, setReport] = useState<PriceReport | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.portfolio().then(setWorth).catch(() => setWorth(null));
  }, []);
  useEffect(load, [load]);

  async function refresh() {
    setRefreshing(true);
    setError(null);
    try {
      setReport(await api.refreshPrices());
      load();
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Les cours n'ont pas pu être actualisés.");
    } finally {
      setRefreshing(false);
    }
  }

  if (!worth) return null;

  if (worth.envelopes.length === 0 && worth.cashAccounts.length === 0) {
    return (
      <section className="empty">
        <h1>Votre patrimoine apparaîtra ici</h1>
        <p className="muted">
          Importez un relevé de comptes, l'évaluation de votre PEA, le bulletin annuel de votre assurance vie ou un avis d'opération de votre PEE.
        </p>
        <button className="primary" onClick={onImport}>
          Importer un relevé
        </button>
      </section>
    );
  }

  return (
    <section className="page">
      <header className="page-header">
        <h1>Patrimoine</h1>
      </header>

      <dl className="figures figures--three">
        <div className="figure">
          <dt>Patrimoine total</dt>
          <dd className="figure-value">{amount(worth.totalCents)}</dd>
        </div>
        <div className="figure">
          <dt>Comptes et livrets</dt>
          <dd className="figure-value">{amount(worth.liquidCents)}</dd>
        </div>
        <div className="figure">
          <dt>Investissements</dt>
          <dd className="figure-value">{amount(worth.investedCents)}</dd>
        </div>
      </dl>

      {worth.monthly.length >= 2 && <MonthlyChart months={worth.monthly} />}
      {worth.allocation.some((a) => a.valueCents > 0) && <AllocationPanel slices={worth.allocation} />}

      <section className="prices">
        <div className="prices-header">
          <p className="muted">
            Les cours sont demandés à Yahoo Finance avec le seul code ISIN de chaque support : ni quantité ni montant ne
            quitte votre ordinateur.
          </p>
          <button className="secondary" onClick={refresh} disabled={refreshing || worth.envelopes.length === 0}>
            {refreshing ? "Actualisation…" : "Actualiser les cours"}
          </button>
        </div>
        {error && <p className="form-error">{error}</p>}
        {report && (
          <div className="price-report">
            <p>
              {report.found} support{report.found > 1 ? "s" : ""} coté{report.found > 1 ? "s" : ""} sur {report.checked}{" "}
              trouvé{report.found > 1 ? "s" : ""}. Les autres gardent la valeur de leur dernier relevé.
            </p>
            {report.results.some((r) => r.status !== "FOUND") && (
              <ul>
                {report.results
                  .filter((r) => r.status !== "FOUND")
                  .map((r) => (
                    <li key={r.isin}>
                      <span className="cell-title">{r.name}</span>
                      <span className="cell-meta">
                        {r.isin} · {statusLabel[r.status]}
                      </span>
                    </li>
                  ))}
              </ul>
            )}
          </div>
        )}
      </section>

      {worth.envelopes.map((envelope) => (
        <EnvelopeSection
          key={envelope.accountId}
          envelope={envelope}
          onSaved={() => {
            load();
            onChanged();
          }}
        />
      ))}

      {worth.cashAccounts.length > 0 && (
        <section className="settings-section">
          <h2>Comptes et livrets</h2>
          <table>
            <tbody>
              {worth.cashAccounts.map((account) => (
                <tr key={account.accountId}>
                  <td className="category-top">{account.name}</td>
                  <td className="muted">au {longDate(account.balanceDate)}</td>
                  <td className="num">
                    <Amount cents={account.balanceCents} />
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

function sourceText(envelope: Envelope): string {
  switch (envelope.valueSource) {
    case "ESTIMATED":
      return `Estimée au ${longDate(envelope.valueDate)} avec les derniers cours (relevé du ${longDate(envelope.statementDate)})`;
    case "MANUAL":
      return `Valeur saisie au ${longDate(envelope.valueDate)}`;
    default:
      return `Relevé du ${longDate(envelope.statementDate)}`;
  }
}

function EnvelopeSection({ envelope, onSaved }: { envelope: Envelope; onSaved: () => void }) {
  const [open, setOpen] = useState(true);
  return (
    <section className="envelope">
      <header className="envelope-header">
        <div>
          <h2>{envelope.name}</h2>
          <p className="cell-meta">
            {envelope.institution} · {envelope.numberMasked}
          </p>
        </div>
        <div className="envelope-value">
          <span className="envelope-amount">{amount(envelope.valueCents)}</span>
          <span className={`cell-meta ${envelope.valueSource === "ESTIMATED" ? "estimated" : ""}`}>{sourceText(envelope)}</span>
        </div>
      </header>

      <dl className="envelope-facts">
        {envelope.contributionsCents !== null && (
          <div>
            <dt>Versements</dt>
            <dd>{amount(envelope.contributionsCents)}</dd>
          </div>
        )}
        {envelope.gainCents !== null && (
          <div>
            <dt>Plus-value</dt>
            <dd>
              <Amount cents={envelope.gainCents} signed />
              {envelope.gainRatio !== null && <span className="muted"> ({percent(envelope.gainRatio)})</span>}
            </dd>
          </div>
        )}
        {envelope.performance && (
          <div>
            <dt>{envelope.performance.annualizedRate !== null ? "Rendement annualisé" : "Rendement"}</dt>
            <dd title="Taux de rendement interne : versements et retraits déduits, placés au milieu de chaque intervalle entre deux relevés.">
              {rate.format(envelope.performance.annualizedRate ?? envelope.performance.periodRate)}
              <span className="muted"> depuis le {longDate(envelope.performance.sinceDate)}</span>
            </dd>
          </div>
        )}
        {envelope.cashCents > 0 && (
          <div>
            <dt>Liquidités</dt>
            <dd>{amount(envelope.cashCents)}</dd>
          </div>
        )}
      </dl>

      {envelope.availability.length > 0 && <AvailabilityPanel parts={envelope.availability} statementDate={envelope.statementDate} />}

      {envelope.type === "LIFE_INSURANCE" && envelope.valueSource === "ESTIMATED" && (
        <p className="hint">
          Avec un mandat d'arbitrage, l'assureur modifie les parts en cours d'année : cette estimation reste indicative
          jusqu'au prochain bulletin.
        </p>
      )}

      <button className="link" onClick={() => setOpen(!open)} aria-expanded={open}>
        {open ? "Masquer" : "Afficher"} {envelope.holdings.length === 1 ? "le support" : `les ${envelope.holdings.length} supports`}
      </button>

      {open && (
        <div className="table-scroll">
          <table className="holdings">
            <thead>
              <tr>
                <th>Support</th>
                <th className="num">Parts</th>
                <th className="num">Cours</th>
                <th className="num">Valeur</th>
                <th className="num">+/- latente au relevé</th>
              </tr>
            </thead>
            <tbody>
              {envelope.holdings.map((h) => (
                <tr key={h.isin ?? h.name}>
                  <td>
                    <span className="cell-title">{h.name}</span>
                    <span className="cell-meta">{h.isin ?? noIsinLabel(h.kind)}</span>
                  </td>
                  <td className="num">{h.quantity ? quantity.format(Number(h.quantity)) : "—"}</td>
                  <td className="num">
                    {h.price ? (
                      <>
                        {unitPrice.format(Number(h.price))} €
                        {h.priceDate && <span className={`cell-meta ${h.repriced ? "estimated" : ""}`}>{shortDate(h.priceDate)}</span>}
                      </>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="num">
                    <Amount cents={h.currentValueCents} />
                  </td>
                  <td className="num">{h.unrealizedCents !== null ? <Amount cents={h.unrealizedCents} signed /> : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ManualValuationForm envelope={envelope} onSaved={onSaved} />
    </section>
  );
}

/** Épargne salariale : ce qui est disponible aujourd'hui, puis les montants bloqués par date de déblocage. */
function AvailabilityPanel({ parts, statementDate }: { parts: Availability[]; statementDate: string }) {
  const today = new Date().toISOString().slice(0, 10);
  const available = parts.filter((p) => p.availableOn <= today).reduce((sum, p) => sum + p.valueCents, 0);
  const locked = parts.filter((p) => p.availableOn > today);
  const total = parts.reduce((sum, p) => sum + p.valueCents, 0);
  const year = new Intl.DateTimeFormat("fr-FR", { month: "short", year: "numeric" });
  return (
    <section className="availability" aria-label="Disponibilité des avoirs">
      <p className="muted">Disponibilité au relevé du {longDate(statementDate)}</p>
      <div className="availability-bar" role="img" aria-label="Répartition disponible et bloqué">
        {available > 0 && <span className="availability-free" style={{ flexGrow: available / total }} />}
        {locked.map((p) => (
          <span key={p.availableOn} className="availability-locked" style={{ flexGrow: p.valueCents / total }} />
        ))}
      </div>
      <dl className="envelope-facts">
        <div>
          <dt>Disponible</dt>
          <dd>{amount(available)}</dd>
        </div>
        {locked.map((p) => (
          <div key={p.availableOn}>
            <dt>Bloqué jusqu'en {year.format(new Date(`${p.availableOn}T12:00:00`))}</dt>
            <dd>{amount(p.valueCents)}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function ManualValuationForm({ envelope, onSaved }: { envelope: Envelope; onSaved: () => void }) {
  const today = new Date().toISOString().slice(0, 10);
  const [date, setDate] = useState(today);
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);
  const cents = parseEuros(value);
  const valid = cents !== null && !Number.isNaN(cents) && date !== "";

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    setError(null);
    try {
      await api.addValuation(envelope.accountId, date, cents!);
      setValue("");
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La valeur n'a pas été enregistrée.");
    }
  }

  return (
    <form className="inline-form" onSubmit={submit}>
      <span className="muted inline-form-label">Valeur lue sur votre espace client :</span>
      <input type="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} aria-label="Date de la valeur" />
      <input inputMode="decimal" placeholder="Montant en €" value={value} onChange={(e) => setValue(e.target.value)} aria-label="Valeur en euros" />
      <button className="secondary" type="submit" disabled={!valid}>
        Enregistrer
      </button>
      {error && <p className="form-error">{error}</p>}
    </form>
  );
}

/** Patrimoine en fin de mois : comptes et livrets en bas, investissements au-dessus. */
function MonthlyChart({ months }: { months: NetWorth["monthly"] }) {
  const [active, setActive] = useState(months.length - 1);
  const width = 720;
  const height = 200;
  const pad = { top: 12, bottom: 24, left: 44, right: 4 };
  const max = Math.max(...months.map((m) => Math.max(m.liquidCents, 0) + Math.max(m.investedCents, 0)), 1);
  const step = niceStep(max / 3);
  const top = Math.ceil(max / step) * step;
  const plotH = height - pad.top - pad.bottom;
  const slot = (width - pad.left - pad.right) / months.length;
  const barW = Math.max(Math.min(slot * 0.7, 28), 2);
  const y = (v: number) => pad.top + plotH - (v / top) * plotH;
  const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step);
  const labelEvery = Math.ceil(months.length / 8);
  const current = months[Math.min(active, months.length - 1)];

  return (
    <section className="history-chart" aria-label="Patrimoine en fin de mois">
      <div className="history-chart-header">
        <h2>Évolution</h2>
        <span className="legend">
          <span className="swatch" style={{ background: "var(--series-1)" }} />
          Comptes et livrets
          <span className="swatch" style={{ background: "var(--series-2)", marginLeft: 10 }} />
          Investissements
        </span>
      </div>
      <p className="worth-readout" aria-live="polite">
        {active >= months.length - 1 ? "Aujourd'hui" : `Fin ${monthName.format(new Date(`${current.month}-15T12:00:00`))}`}{" "}
        · <strong>{amount(current.totalCents)}</strong> · comptes {amount(current.liquidCents)} · investissements {amount(current.investedCents)}
        {current.estimated && " (estimé d'après les derniers cours)"}
      </p>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Patrimoine en fin de mois, comptes et investissements" onMouseLeave={() => setActive(months.length - 1)}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} className="grid" />
            <text x={pad.left - 6} y={y(t) + 4} textAnchor="end" className="tick">
              {compact.format(t / 100)}
            </text>
          </g>
        ))}
        {months.map((m, i) => {
          const x = pad.left + i * slot + (slot - barW) / 2;
          const liquid = Math.max(m.liquidCents, 0);
          const invested = Math.max(m.investedCents, 0);
          const liquidTop = y(liquid);
          const investedTop = y(liquid + invested);
          const gap = liquid > 0 && invested > 0 ? 2 : 0;
          return (
            <g
              key={m.month}
              className={`worth-month month ${i === active ? "month--active" : ""}`}
              onMouseEnter={() => setActive(i)}
              onFocus={() => setActive(i)}
              tabIndex={0}
            >
              <rect x={pad.left + i * slot} y={pad.top} width={slot} height={plotH} className="month-hit" />
              {liquid > 0 && (
                <rect x={x} y={liquidTop} width={barW} height={Math.max(y(0) - liquidTop, 0)} className={`seg seg--liquid ${m.estimated ? "seg--estimated" : ""}`} />
              )}
              {invested > 0 && (
                <rect x={x} y={investedTop} width={barW} height={Math.max(liquidTop - investedTop - gap, 0)} rx={Math.min(4, barW / 2)} className={`seg seg--invested ${m.estimated ? "seg--estimated" : ""}`} />
              )}
              {((i % labelEvery === 0 && months.length - 1 - i >= labelEvery) || i === months.length - 1) && (
                <text x={pad.left + i * slot + slot / 2} y={height - 6} textAnchor="middle" className="month-label">
                  {monthName.format(new Date(`${m.month}-15T12:00:00`))}
                </text>
              )}
              <title>{`${m.month} · ${amount(m.totalCents)}`}</title>
            </g>
          );
        })}
      </svg>
      <p className="muted">Chaque compte compte pour sa dernière valeur connue à la fin du mois, à partir de son premier relevé.</p>
    </section>
  );
}

function niceStep(raw: number): number {
  const power = 10 ** Math.floor(Math.log10(Math.max(raw, 1)));
  const n = raw / power;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * power;
}

/** Répartition par classe d'actifs : barre empilée et tableau des montants (la couleur n'est jamais seule). */
function AllocationPanel({ slices }: { slices: NetWorth["allocation"] }) {
  const positive = assetClasses
    .map((c) => ({ ...c, cents: slices.find((s) => s.assetClass === c.key)?.valueCents ?? 0 }))
    .filter((c) => c.cents !== 0);
  const total = positive.reduce((sum, c) => sum + Math.max(c.cents, 0), 0);
  return (
    <section className="allocation" aria-labelledby="allocation-title">
      <h2 id="allocation-title">Répartition</h2>
      <div className="allocation-bar" role="img" aria-label="Répartition du patrimoine par classe d'actifs">
        {positive
          .filter((c) => c.cents > 0)
          .map((c) => (
            <span key={c.key} style={{ flexGrow: c.cents, background: c.color }} title={`${c.label} · ${amount(c.cents)} · ${share.format(c.cents / total)}`} />
          ))}
      </div>
      <table>
        <tbody>
          {positive.map((c) => (
            <tr key={c.key}>
              <td>
                <span className="swatch" style={{ background: c.color }} />
                {c.label}
              </td>
              <td className="num">
                <Amount cents={c.cents} />
              </td>
              <td className="num muted">{c.cents > 0 && total > 0 ? share.format(c.cents / total) : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">
        Une enveloppe se répartit comme les supports de son dernier relevé. Les supports dont le nom évoque le monétaire
        (« monétaire », « trésorerie ») sont comptés à part.
      </p>
    </section>
  );
}
