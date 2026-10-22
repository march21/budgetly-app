import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type RecurringOverview, type RecurringSeries } from "./api";
import { Amount } from "./Amount";
import { amount, longDate, shortDate } from "./format";

/** Charges fixes, abonnements et revenus réguliers, détectés dans les relevés importés. */
export function RecurringPage({ onImport }: { onImport: () => void }) {
  const [overview, setOverview] = useState<RecurringOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showStopped, setShowStopped] = useState(false);

  const load = useCallback(() => {
    api.recurring().then(setOverview).catch(() => setOverview(null));
  }, []);
  useEffect(load, [load]);

  async function ignore(series: RecurringSeries, ignored: boolean) {
    setError(null);
    try {
      await api.ignoreSeries(series.key, ignored);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La modification n'a pas été enregistrée.");
    }
  }

  if (!overview) return null;
  if (!overview.horizon) {
    return (
      <section className="empty">
        <h1>Vos charges fixes apparaîtront ici</h1>
        <p className="muted">Il faut au moins deux mois de relevés pour reconnaître un prélèvement qui revient.</p>
        <button className="primary" onClick={onImport}>
          Importer un relevé
        </button>
      </section>
    );
  }

  const active = overview.series.filter((s) => s.status === "ACTIVE");
  const charges = active.filter((s) => !s.credit && s.categoryKind !== "SAVINGS");
  const savings = active.filter((s) => !s.credit && s.categoryKind === "SAVINGS");
  const income = active.filter((s) => s.credit);
  const stopped = overview.series.filter((s) => s.status === "STOPPED");
  const alerts = active.filter((s) => s.isNew || s.priceChanged);

  return (
    <section className="page">
      <header className="page-header">
        <h1>Charges fixes</h1>
        <p className="muted">
          Prélèvements et revenus qui reviennent chaque mois, reconnus d'après vos relevés jusqu'au {longDate(overview.horizon)}.
        </p>
      </header>

      <dl className="figures figures--three">
        <div className="figure">
          <dt>Charges fixes par mois</dt>
          <dd className="figure-value figure-value--debit">{amount(overview.monthlyFixedCents)}</dd>
        </div>
        <div className="figure">
          <dt>Revenus réguliers par mois</dt>
          <dd className="figure-value figure-value--credit">{amount(overview.monthlyIncomeCents)}</dd>
        </div>
        <div className="figure">
          <dt>Reste après charges fixes</dt>
          <dd className="figure-value">{amount(overview.monthlyIncomeCents - overview.monthlyFixedCents)}</dd>
        </div>
      </dl>

      {error && <p className="form-error">{error}</p>}

      {alerts.length > 0 && (
        <section className="recurring-alerts" aria-label="À vérifier">
          {alerts.map((s) => (
            <div key={s.key} className="notice">
              <p>
                {s.priceChanged ? (
                  <>
                    <strong>{s.pattern}</strong> passe de <Amount cents={s.previousAmountCents!} signed /> à{" "}
                    <Amount cents={s.lastAmountCents} signed /> (le {shortDate(s.lastDate)}).
                  </>
                ) : (
                  <>
                    Nouveau prélèvement régulier : <strong>{s.pattern}</strong>, <Amount cents={s.typicalAmountCents} signed /> par
                    mois depuis le {shortDate(s.firstDate)}.
                  </>
                )}
              </p>
            </div>
          ))}
        </section>
      )}

      <SeriesTable title="Charges et abonnements" series={charges} onIgnore={(s) => ignore(s, true)} />
      {savings.length > 0 && <SeriesTable title="Versements d'épargne réguliers" series={savings} onIgnore={(s) => ignore(s, true)} />}
      {income.length > 0 && <SeriesTable title="Revenus réguliers" series={income} onIgnore={(s) => ignore(s, true)} />}

      {stopped.length > 0 && (
        <section className="settings-section">
          <button className="link" onClick={() => setShowStopped(!showStopped)} aria-expanded={showStopped}>
            {showStopped ? "Masquer" : "Afficher"} {stopped.length === 1 ? "la série arrêtée" : `les ${stopped.length} séries arrêtées`}
          </button>
          {showStopped && <SeriesTable title="Arrêtées" series={stopped} onIgnore={(s) => ignore(s, true)} />}
        </section>
      )}

      {overview.ignored.length > 0 && (
        <section className="settings-section">
          <h2>Écartées</h2>
          <p className="muted">Vous avez indiqué que ce ne sont pas des charges fixes : elles ne comptent ni ici ni dans la projection.</p>
          <table>
            <tbody>
              {overview.ignored.map((s) => (
                <tr key={s.key}>
                  <td className="cell-title">{s.pattern}</td>
                  <td className="num">
                    <Amount cents={s.typicalAmountCents} signed />
                  </td>
                  <td className="row-actions">
                    <button className="link" onClick={() => ignore(s, false)}>
                      Rétablir
                    </button>
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

function SeriesTable({ title, series, onIgnore }: { title: string; series: RecurringSeries[]; onIgnore: (s: RecurringSeries) => void }) {
  if (series.length === 0) {
    return (
      <section className="settings-section">
        <h2>{title}</h2>
        <p className="muted">Rien de reconnu pour l'instant.</p>
      </section>
    );
  }
  return (
    <section className="settings-section">
      <h2>{title}</h2>
      <div className="table-scroll">
        <table className="recurring-table">
          <thead>
            <tr>
              <th>Libellé</th>
              <th>Catégorie</th>
              <th className="num">Montant</th>
              <th>Prochaine</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {series.map((s) => (
              <tr key={s.key}>
                <td>
                  <span className="cell-title">{s.pattern}</span>
                  <span className="cell-meta">
                    {s.occurrences} fois depuis le {shortDate(s.firstDate)}
                    {s.variable && " · montant variable"}
                    {s.isNew && " · nouveau"}
                  </span>
                </td>
                <td className="muted">{s.categoryName ?? "À classer"}</td>
                <td className="num">
                  <Amount cents={s.variable ? s.typicalAmountCents : s.lastAmountCents} signed />
                  {s.variable && <span className="cell-meta">en moyenne</span>}
                </td>
                <td>{s.status === "ACTIVE" ? `vers le ${shortDate(s.nextDate)}` : `dernière le ${shortDate(s.lastDate)}`}</td>
                <td className="row-actions">
                  <button className="link" onClick={() => onIgnore(s)} title="Ce n'est pas une charge fixe">
                    Écarter
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
