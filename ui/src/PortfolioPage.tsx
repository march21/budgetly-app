import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type Availability, type Envelope, type NetWorth, type PriceReport } from "./api";
import { Amount } from "./Amount";
import { amount, longDate, noIsinLabel, parseEuros, percent, shortDate } from "./format";

const statusLabel: Record<string, string> = {
  FOUND: "Cours trouvé",
  NOT_FOUND: "Inconnu de la source",
  MISMATCH: "Écarté : ne correspond pas au relevé",
  ERROR: "Source injoignable",
  UNAVAILABLE: "Aucune source configurée",
};

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

      {worth.history.length >= 2 && <NetWorthChart points={worth.history} />}

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

/** Patrimoine total aux dates connues ; le dernier point est estimé quand il s'appuie sur des cours récents. */
function NetWorthChart({ points }: { points: NetWorth["history"] }) {
  const width = 720;
  const height = 180;
  const pad = { top: 16, bottom: 26, left: 8, right: 8 };
  const times = points.map((p) => new Date(`${p.date}T12:00:00`).getTime());
  const min = Math.min(...points.map((p) => p.totalCents));
  const max = Math.max(...points.map((p) => p.totalCents));
  const span = Math.max(max - min, max * 0.05, 1);
  const lo = min - span * 0.1;
  const hi = max + span * 0.1;
  const x = (t: number) => pad.left + ((t - times[0]) / Math.max(times[times.length - 1] - times[0], 1)) * (width - pad.left - pad.right);
  const y = (v: number) => pad.top + (1 - (v - lo) / (hi - lo)) * (height - pad.top - pad.bottom);
  const path = points.map((p, i) => `${i === 0 ? "M" : "L"}${x(times[i]).toFixed(1)},${y(p.totalCents).toFixed(1)}`).join(" ");
  const last = points[points.length - 1];

  return (
    <section className="history-chart" aria-label="Évolution du patrimoine">
      <div className="history-chart-header">
        <h2>Évolution</h2>
        <p className="muted">Chaque compte compte pour sa dernière valeur connue à chaque date.</p>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Patrimoine total dans le temps">
        <path d={path} className="worth-line" />
        {points.map((p, i) => (
          <circle key={p.date} cx={x(times[i])} cy={y(p.totalCents)} r={p.estimated ? 4 : 3} className={p.estimated ? "worth-point worth-point--estimated" : "worth-point"}>
            <title>{`${longDate(p.date)} · ${amount(p.totalCents)}${p.estimated ? " (estimé)" : ""}`}</title>
          </circle>
        ))}
        <text x={x(times[0])} y={height - 6} className="month-label">
          {shortDate(points[0].date)}
        </text>
        <text x={x(times[times.length - 1])} y={height - 6} textAnchor="end" className="month-label">
          {shortDate(last.date)}
        </text>
      </svg>
    </section>
  );
}
