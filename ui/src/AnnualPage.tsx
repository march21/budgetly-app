import { useEffect, useState } from "react";
import { api, type AnnualLine, type AnnualReview } from "./api";
import { Amount } from "./Amount";
import { amount, percent, signedAmount } from "./format";
import { TaxAidPanel } from "./TaxAid";

const monthShort = new Intl.DateTimeFormat("fr-FR", { month: "short" });
const monthLong = new Intl.DateTimeFormat("fr-FR", { month: "long" });
const ratio = new Intl.NumberFormat("fr-FR", { style: "percent", maximumFractionDigits: 0, signDisplay: "exceptZero" });

function change(current: number, previous: number): string {
  if (previous === 0) return current === 0 ? "—" : "nouveau";
  return ratio.format((current - previous) / previous);
}

export function AnnualPage() {
  const thisYear = new Date().getFullYear();
  const [year, setYear] = useState(thisYear);
  const [review, setReview] = useState<AnnualReview | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    setError(false);
    api.annual(year).then(setReview).catch(() => setError(true));
  }, [year]);

  const years = Array.from({ length: 6 }, (_, i) => thisYear - i);
  const through = review ? new Date(`${review.through}-15T12:00:00`) : null;
  const period = review && through ? (through.getMonth() === 11 ? "année complète" : `de janvier à ${monthLong.format(through)}`) : "";

  return (
    <section className="page">
      <header className="page-header">
        <h1>Bilan de l'année</h1>
        <div className="annual-controls">
          <label className="field field--inline">
            <span>Année</span>
            <select value={year} onChange={(e) => setYear(Number(e.target.value))}>
              {years.map((y) => (
                <option key={y} value={y}>
                  {y}
                </option>
              ))}
            </select>
          </label>
          {review && review.monthsWithData > 0 && (
            <a className="secondary button-link" href={api.annualPdfUrl(year)} download>
              Télécharger le bilan (PDF)
            </a>
          )}
        </div>
      </header>

      {error && <p className="form-error">Le bilan n'a pas pu être calculé.</p>}
      {review && review.monthsWithData === 0 && <p className="muted">Aucune opération importée pour {year}.</p>}
      {review && review.monthsWithData > 0 && (
        <>
          <p className="muted">
            {year}, {period}, comparé à la même période de {year - 1}
            {year === thisYear && " (jusqu'au dernier mois complet)"}. Le PDF est généré sur cet ordinateur et n'est pas
            chiffré : rangez-le en conséquence.
          </p>
          <dl className="figures">
            <Figure label="Revenus" cents={review.current.incomeCents} previous={review.previous.incomeCents} />
            <Figure label="Dépenses" cents={review.current.expenseCents} previous={review.previous.expenseCents} />
            <Figure label="Épargne versée" cents={review.current.savingsCents} previous={review.previous.savingsCents} />
            <div className="figure">
              <dt>Taux d'épargne</dt>
              <dd className="figure-value">{review.current.savingsRate === null ? "—" : percent(review.current.savingsRate)}</dd>
              <dd className="figure-compare">
                {review.previous.savingsRate === null ? `pas de revenus en ${year - 1}` : `${percent(review.previous.savingsRate)} en ${year - 1}`}
              </dd>
            </div>
          </dl>

          {(review.increases.length > 0 || review.decreases.length > 0) && (
            <div className="annual-moves">
              <Moves title="Plus fortes hausses" lines={review.increases} />
              <Moves title="Plus fortes baisses" lines={review.decreases} />
            </div>
          )}

          <MonthsTable review={review} />
          <LinesTable title="Dépenses par catégorie" lines={review.expenses} year={year} />
          {review.income.length > 0 && <LinesTable title="Revenus par catégorie" lines={review.income} year={year} />}
          <TaxAidPanel year={year} />
        </>
      )}
    </section>
  );
}

function Figure({ label, cents, previous }: { label: string; cents: number; previous: number }) {
  return (
    <div className="figure">
      <dt>{label}</dt>
      <dd className="figure-value">{amount(cents)}</dd>
      <dd className="figure-compare">
        {amount(previous)} l'an dernier · {change(cents, previous)}
      </dd>
    </div>
  );
}

function Moves({ title, lines }: { title: string; lines: AnnualLine[] }) {
  if (lines.length === 0) return null;
  return (
    <section className="annual-move">
      <h2>{title}</h2>
      <ul>
        {lines.map((l) => (
          <li key={l.categoryId ?? l.name}>
            <span>{l.name}</span>
            <span className="num">
              {signedAmount(l.deltaCents)} <span className="muted">({change(l.cents, l.previousCents)})</span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function LinesTable({ title, lines, year }: { title: string; lines: AnnualLine[]; year: number }) {
  return (
    <section className="settings-section">
      <h2>{title}</h2>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Catégorie</th>
              <th className="num">{year}</th>
              <th className="num">{year - 1}</th>
              <th className="num">Écart</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.categoryId ?? l.name}>
                <td>{l.name}</td>
                <td className="num">
                  <Amount cents={l.cents} />
                </td>
                <td className="num muted">{amount(l.previousCents)}</td>
                <td className="num">
                  {signedAmount(l.deltaCents)} <span className="muted">{change(l.cents, l.previousCents)}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function MonthsTable({ review }: { review: AnnualReview }) {
  return (
    <section className="settings-section">
      <h2>Mois par mois</h2>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Mois</th>
              <th className="num">Revenus</th>
              <th className="num">Dépenses</th>
              <th className="num">Dépenses {review.year - 1}</th>
            </tr>
          </thead>
          <tbody>
            {review.months.map((m) => (
              <tr key={m.month}>
                <td>{monthShort.format(new Date(`${m.month}-15T12:00:00`))}</td>
                <td className="num">{amount(m.incomeCents)}</td>
                <td className="num">{amount(m.expenseCents)}</td>
                <td className="num muted">{amount(m.previousExpenseCents)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
