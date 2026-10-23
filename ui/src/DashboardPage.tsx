import { useEffect, useState } from "react";
import { api, type Account, type CategoryLine, type MonthProjection, type MonthSummary, type RecurringOverview, type SummaryHistory } from "./api";
import { Amount } from "./Amount";
import { GoalsPanel } from "./Goals";
import { amount, currentMonth, longDate, monthAbbrev, monthLabel, percent, shiftMonth, shortDate } from "./format";

type Props = {
  accounts: Account[];
  onImport: () => void;
  onShowRecurring: () => void;
  month: string;
  onMonthChange: (month: string) => void;
  onShowUncategorized: () => void;
  onShowCategory: (categoryId: number) => void;
};

export function DashboardPage({ accounts, onImport, onShowRecurring, month, onMonthChange, onShowUncategorized, onShowCategory }: Props) {
  const [summary, setSummary] = useState<MonthSummary | null>(null);
  const [history, setHistory] = useState<SummaryHistory | null>(null);
  const [projection, setProjection] = useState<MonthProjection | null>(null);
  const [recurring, setRecurring] = useState<RecurringOverview | null>(null);

  useEffect(() => {
    api.recurring().then(setRecurring).catch(() => setRecurring(null));
  }, []);

  // Projection : mois en cours, ou mois de la dernière opération connue (relevés importés avec retard).
  const projectable = month === currentMonth() || (recurring?.horizon ?? "").startsWith(month);
  useEffect(() => {
    if (!projectable) {
      setProjection(null);
      return;
    }
    api.projection(month).then(setProjection).catch(() => setProjection(null));
  }, [month, projectable]);

  useEffect(() => {
    api.summary(month).then(setSummary).catch(() => setSummary(null));
    api.history(month).then(setHistory).catch(() => setHistory(null));
  }, [month]);

  return (
    <section className="page">
      <header className="page-header month-header">
        <button className="icon-button" onClick={() => onMonthChange(shiftMonth(month, -1))} aria-label="Mois précédent">
          ‹
        </button>
        <h1>{monthLabel(month)}</h1>
        <button className="icon-button" onClick={() => onMonthChange(shiftMonth(month, 1))} aria-label="Mois suivant">
          ›
        </button>
      </header>

      <StaleStatements accounts={accounts} onImport={onImport} />
      {recurring && <RecurringAlerts overview={recurring} onShow={onShowRecurring} />}

      {summary && (
        <>
          <dl className="figures">
            <Figure label="Revenus" cents={summary.incomeCents} tone="credit" />
            <Figure label="Dépenses" cents={summary.expenseCents} tone="debit" />
            <Figure label="Épargne versée" cents={summary.savingsCents} />
            <div className="figure">
              <dt>Taux d'épargne</dt>
              <dd className="figure-value">{summary.savingsRate === null ? "—" : percent(summary.savingsRate)}</dd>
              <dd className="figure-note">
                Reste sur les comptes : <Amount cents={summary.remainingCents} signed />
              </dd>
            </div>
          </dl>

          {summary.uncategorizedCount > 0 && (
            <div className="notice">
              <p>
                {summary.uncategorizedCount} opération{summary.uncategorizedCount > 1 ? "s" : ""} de ce mois{" "}
                {summary.uncategorizedCount > 1 ? "ne sont" : "n'est"} pas encore classée
                {summary.uncategorizedCount > 1 ? "s" : ""} : elles comptent comme dépense ou revenu selon leur signe.
              </p>
              <button className="link" onClick={onShowUncategorized}>
                Les classer
              </button>
            </div>
          )}

          {projection && <ProjectionPanel projection={projection} onShowRecurring={onShowRecurring} />}

          <div className="dashboard-columns">
            <section className="breakdown" aria-label="Dépenses par catégorie">
              <h2>Où va l'argent</h2>
              {summary.expenses.length === 0 ? (
                <p className="muted">Aucune dépense ce mois-ci.</p>
              ) : (
                <Breakdown lines={summary.expenses} onShowCategory={onShowCategory} withBudget />
              )}
            </section>

            <section className="breakdown" aria-label="Revenus par catégorie">
              <h2>D'où il vient</h2>
              {summary.income.length === 0 ? (
                <p className="muted">Aucun revenu ce mois-ci.</p>
              ) : (
                <Breakdown lines={summary.income} onShowCategory={onShowCategory} />
              )}
              {summary.internalTransferCount > 0 && (
                <p className="breakdown-note">
                  {summary.internalTransferCount} virement{summary.internalTransferCount > 1 ? "s" : ""} entre vos comptes
                  non compté{summary.internalTransferCount > 1 ? "s" : ""}.
                </p>
              )}
            </section>
          </div>
        </>
      )}

      {history && <HistoryChart history={history} selected={month} onSelect={onMonthChange} />}
      <GoalsPanel accounts={accounts} />
    </section>
  );
}

function Figure({ label, cents, tone }: { label: string; cents: number; tone?: "credit" | "debit" }) {
  return (
    <div className="figure">
      <dt>{label}</dt>
      <dd className={`figure-value ${tone ? `figure-value--${tone}` : ""}`}>{amount(cents)}</dd>
    </div>
  );
}

function Breakdown({
  lines,
  withBudget = false,
  onShowCategory,
}: {
  lines: CategoryLine[];
  withBudget?: boolean;
  onShowCategory: (id: number) => void;
}) {
  const [open, setOpen] = useState<number | null>(null);
  const max = Math.max(...lines.map((l) => Math.max(l.cents, l.budgetCents ?? 0)), 1);

  return (
    <ul className="breakdown-list">
      {lines.map((line) => {
        const key = line.categoryId ?? -1;
        const over = withBudget && line.budgetCents !== null && line.cents > line.budgetCents;
        return (
          <li key={key} className={line.categoryId === null ? "breakdown-row breakdown-row--unclassified" : "breakdown-row"}>
            <button
              className="breakdown-main"
              onClick={() => (line.children.length > 0 ? setOpen(open === key ? null : key) : line.categoryId && onShowCategory(line.categoryId))}
              aria-expanded={line.children.length > 0 ? open === key : undefined}
            >
              <span className="breakdown-name">{line.name}</span>
              <span className="breakdown-amount">
                <Amount cents={line.cents} />
                {withBudget && line.budgetCents !== null && (
                  <span className={over ? "budget budget--over" : "budget"}> / {amount(line.budgetCents)}</span>
                )}
              </span>
              <span className="bar" aria-hidden="true">
                <span
                  className={`bar-fill ${over ? "bar-fill--over" : ""}`}
                  style={{ width: `${(Math.max(line.cents, 0) / max) * 100}%` }}
                />
                {withBudget && line.budgetCents !== null && (
                  <span className="bar-budget" style={{ left: `${(line.budgetCents / max) * 100}%` }} />
                )}
              </span>
            </button>
            {open === key && (
              <ul className="breakdown-children">
                {line.children.map((child) => (
                  <li key={child.categoryId ?? -1}>
                    <button className="breakdown-child" onClick={() => child.categoryId && onShowCategory(child.categoryId)}>
                      <span>{child.name}</span>
                      <Amount cents={child.cents} />
                    </button>
                  </li>
                ))}
                {line.children.length > 0 && line.categoryId && (
                  <li>
                    <button className="link breakdown-all" onClick={() => onShowCategory(line.categoryId!)}>
                      Voir les {line.transactionCount} opérations
                    </button>
                  </li>
                )}
              </ul>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/** Revenus et dépenses sur 12 mois, en barres côte à côte ; le mois affiché est mis en avant. */
function HistoryChart({
  history,
  selected,
  onSelect,
}: {
  history: SummaryHistory;
  selected: string;
  onSelect: (month: string) => void;
}) {
  const width = 720;
  const height = 200;
  const top = 12;
  const bottom = 28;
  const max = Math.max(...history.months.flatMap((m) => [m.incomeCents, m.expenseCents]), 1);
  const slot = width / history.months.length;
  const bar = Math.min(16, slot / 3);
  const y = (cents: number) => top + (height - top - bottom) * (1 - Math.max(cents, 0) / max);
  const hasData = history.months.some((m) => m.incomeCents || m.expenseCents);

  return (
    <section className="history-chart" aria-label="Revenus et dépenses sur 12 mois">
      <div className="history-chart-header">
        <h2>Sur douze mois</h2>
        <p className="legend">
          <span className="legend-swatch legend-swatch--credit" /> Revenus
          <span className="legend-swatch legend-swatch--debit" /> Dépenses
        </p>
      </div>
      {hasData ? (
        <>
          <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Revenus et dépenses par mois">
            <line x1="0" x2={width} y1={height - bottom} y2={height - bottom} className="axis" />
            {history.months.map((m, i) => {
              const x = i * slot + slot / 2;
              const active = m.month === selected;
              return (
                <g key={m.month} className={active ? "month month--active" : "month"} onClick={() => onSelect(m.month)}>
                  <rect x={i * slot} y={0} width={slot} height={height} className="month-hit" />
                  <rect x={x - bar - 1} y={y(m.incomeCents)} width={bar} height={height - bottom - y(m.incomeCents)} className="bar-credit" rx="2">
                    <title>{`${monthLabel(m.month)} · revenus ${amount(m.incomeCents)}`}</title>
                  </rect>
                  <rect x={x + 1} y={y(m.expenseCents)} width={bar} height={height - bottom - y(m.expenseCents)} className="bar-debit" rx="2">
                    <title>{`${monthLabel(m.month)} · dépenses ${amount(m.expenseCents)}`}</title>
                  </rect>
                  <text x={x} y={height - 9} textAnchor="middle" className="month-label">
                    {monthAbbrev(m.month)}
                  </text>
                </g>
              );
            })}
          </svg>
          <p className="muted history-averages">
            Moyenne des mois renseignés : revenus {amount(history.averageIncomeCents)}, dépenses{" "}
            {amount(history.averageExpenseCents)}, épargne versée {amount(history.averageSavingsCents)}
            {history.averageSavingsRate !== null && <>, taux d'épargne {percent(history.averageSavingsRate)}</>}.
          </p>
        </>
      ) : (
        <p className="muted">L'historique se construit au fil des relevés importés.</p>
      )}
    </section>
  );
}

/** Relevés mensuels : un compte courant ou un livret sans relevé depuis plus de 40 jours en mérite un nouveau. */
const STALE_DAYS = 40;

function StaleStatements({ accounts, onImport }: { accounts: Account[]; onImport: () => void }) {
  const limit = Date.now() - STALE_DAYS * 86_400_000;
  const stale = accounts.filter(
    (a) =>
      a.type !== "PEA" &&
      a.type !== "LIFE_INSURANCE" &&
      a.type !== "PEE" &&
      a.balanceDate !== null &&
      new Date(`${a.balanceDate}T12:00:00`).getTime() < limit,
  );
  if (stale.length === 0) return null;
  const latest = stale.map((a) => a.balanceDate!).sort()[0];
  return (
    <div className="notice">
      <p>
        {stale.length === 1 ? `Aucun relevé de « ${stale[0].name} »` : `Aucun relevé de ${stale.length} comptes`} depuis le{" "}
        {longDate(latest)} : le bilan des mois suivants est incomplet.
      </p>
      <button className="link" onClick={onImport}>
        Importer un relevé
      </button>
    </div>
  );
}

/** Nouveaux prélèvements réguliers et changements de montant : ce qui mérite un coup d'œil. */
function RecurringAlerts({ overview, onShow }: { overview: RecurringOverview; onShow: () => void }) {
  const fresh = overview.series.filter((s) => s.status === "ACTIVE" && s.isNew && !s.credit);
  const changed = overview.series.filter((s) => s.status === "ACTIVE" && s.priceChanged && !s.credit);
  if (fresh.length === 0 && changed.length === 0) return null;
  const parts = [
    fresh.length > 0 && `${fresh.length} nouveau${fresh.length > 1 ? "x" : ""} prélèvement${fresh.length > 1 ? "s" : ""} régulier${fresh.length > 1 ? "s" : ""}`,
    changed.length > 0 && `${changed.length} changement${changed.length > 1 ? "s" : ""} de montant`,
  ].filter(Boolean);
  return (
    <div className="notice">
      <p>{parts.join(" et ")} dans vos charges fixes.</p>
      <button className="link" onClick={onShow}>
        Voir les charges fixes
      </button>
    </div>
  );
}

const budgetStatus: Record<string, string> = {
  OVER: "dépassé",
  AT_RISK: "sera dépassé à ce rythme",
  WATCH: "consommé à plus de 80 %",
};

/** Fin de mois prévue : ce qui reste attendu (charges fixes, revenus, dépenses courantes) et les budgets menacés. */
function ProjectionPanel({ projection: p, onShowRecurring }: { projection: MonthProjection; onShowRecurring: () => void }) {
  const fixed = p.expected.filter((e) => e.amountCents < 0);
  const income = p.expected.filter((e) => e.amountCents > 0);
  const fixedTotal = -fixed.reduce((sum, e) => sum + e.amountCents, 0);
  const incomeTotal = income.reduce((sum, e) => sum + e.amountCents, 0);
  return (
    <section className="projection" aria-label="Fin de mois prévue">
      <div className="projection-header">
        <div>
          <h2>Fin de mois prévue</h2>
          <p className="muted">
            {p.asOf ? `D'après vos relevés jusqu'au ${longDate(p.asOf)}` : "Aucune opération importée ce mois-ci"} ·{" "}
            {p.daysRemaining} jour{p.daysRemaining > 1 ? "s" : ""} restant{p.daysRemaining > 1 ? "s" : ""}
          </p>
        </div>
        {p.projectedCheckingCents !== null && (
          <div className="projection-result">
            <span className="muted">Sur le compte courant à la fin du mois</span>
            <span className={`projection-amount ${p.projectedCheckingCents < 0 ? "projection-amount--low" : ""}`}>
              {amount(p.projectedCheckingCents)}
            </span>
          </div>
        )}
      </div>

      <dl className="projection-steps">
        {p.checkingBalanceCents !== null && (
          <div>
            <dt>Solde au dernier relevé</dt>
            <dd>{amount(p.checkingBalanceCents)}</dd>
          </div>
        )}
        <div>
          <dt>Revenus attendus</dt>
          <dd>
            <Amount cents={incomeTotal} signed />
          </dd>
        </div>
        <div>
          <dt>
            <button className="link" onClick={onShowRecurring}>
              Charges fixes à venir
            </button>
          </dt>
          <dd>
            <Amount cents={-fixedTotal} signed />
          </dd>
        </div>
        <div>
          <dt>Dépenses courantes estimées</dt>
          <dd>
            <Amount cents={-p.variableEstimateCents} signed />
          </dd>
        </div>
      </dl>

      {p.expected.length > 0 && (
        <ul className="projection-expected">
          {p.expected.map((e, i) => (
            <li key={i}>
              <span className="muted">{shortDate(e.date)}</span>
              <span>{e.label}</span>
              <Amount cents={e.amountCents} signed />
            </li>
          ))}
        </ul>
      )}

      {p.budgetAlerts.map((alert) => (
        <div key={alert.categoryId} className={`notice ${alert.status === "OVER" ? "notice--error" : ""}`}>
          <p>
            Budget {alert.name} {budgetStatus[alert.status]} : {amount(alert.spentCents)} sur {amount(alert.budgetCents)}
            {alert.status === "AT_RISK" && `, environ ${amount(alert.projectedCents)} prévus`}.
          </p>
        </div>
      ))}
      <p className="hint">
        Estimation : charges fixes et revenus réguliers reconnus, plus vos dépenses courantes moyennes des trois derniers mois.
      </p>
    </section>
  );
}
