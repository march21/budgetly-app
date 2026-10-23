import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type Installment, type Loan, type LoanRequest } from "./api";
import { amount, longDate, parseEuros, shortDate } from "./format";

const ratePct = (rate: string) => `${rate.replace(".", ",")} %`;

export function LoansPanel({ onChanged }: { onChanged: () => void }) {
  const [loans, setLoans] = useState<Loan[] | null>(null);
  const [editing, setEditing] = useState<Loan | "new" | null>(null);
  const load = () => {
    api.loans().then(setLoans).catch(() => setLoans(null));
  };
  useEffect(load, []);
  const done = () => {
    setEditing(null);
    load();
    onChanged();
  };

  if (!loans) return null;
  return (
    <section className="loans" aria-labelledby="loans-title">
      <div className="goals-header">
        <h2 id="loans-title">Crédits</h2>
        {editing === null && (
          <button className="secondary" onClick={() => setEditing("new")}>
            Ajouter un crédit
          </button>
        )}
      </div>
      {loans.length === 0 && editing === null && (
        <p className="muted">
          Saisissez vos crédits (immobilier, consommation) : leur capital restant dû est déduit du patrimoine, et chaque
          échéance est détaillée en intérêts et capital.
        </p>
      )}
      <ul>
        {loans.map((l) =>
          editing !== "new" && editing?.id === l.id ? (
            <li key={l.id}>
              <LoanForm loan={l} onDone={done} />
            </li>
          ) : (
            <LoanItem key={l.id} loan={l} onEdit={() => setEditing(l)} />
          ),
        )}
        {editing === "new" && (
          <li>
            <LoanForm onDone={done} />
          </li>
        )}
      </ul>
      {loans.length > 0 && (
        <p className="muted">
          Échéances constantes, intérêts calculés chaque mois sur le capital restant dû. Un remboursement anticipé ou un
          différé ne sont pas pris en compte : modifiez le crédit si besoin.
        </p>
      )}
    </section>
  );
}

function LoanItem({ loan, onEdit }: { loan: Loan; onEdit: () => void }) {
  const [schedule, setSchedule] = useState<Installment[] | null>(null);
  const repaid = loan.principalCents - loan.remainingCents;
  const insurance = loan.insuranceMonthlyCents ?? 0;
  async function toggle() {
    setSchedule(schedule ? null : await api.loanSchedule(loan.id));
  }
  return (
    <li className="goal">
      <div className="goal-head">
        <span>
          <span className="cell-title">{loan.name}</span>
          <span className="muted">
            {" "}
            · {loan.lender ? `${loan.lender} · ` : ""}
            {ratePct(loan.annualRate)} sur {loan.termMonths} mois
          </span>
        </span>
        <span>
          <strong>{amount(loan.remainingCents)}</strong> <span className="muted">restant dû sur {amount(loan.principalCents)}</span>
        </span>
      </div>
      <div className="goal-bar" role="progressbar" aria-valuemin={0} aria-valuemax={loan.principalCents} aria-valuenow={repaid} aria-label={`${loan.name} : capital remboursé`}>
        <span style={{ width: `${Math.min(100, (repaid / loan.principalCents) * 100)}%` }} />
      </div>
      <p className="goal-line">
        Échéance {amount(loan.paymentCents)}
        {insurance > 0 && ` + assurance ${amount(insurance)}`}.{" "}
        {loan.next ? (
          <>
            Prochaine le {longDate(loan.next.date)} : {amount(loan.next.interestCents)} d'intérêts, {amount(loan.next.principalCents)} de capital.
          </>
        ) : (
          "Crédit remboursé."
        )}{" "}
        <span className="muted">
          Fin le {longDate(loan.endDate)} · intérêts {amount(loan.interestPaidCents)} payés sur {amount(loan.totalInterestCents)}.
        </span>
      </p>
      {loan.matched && (
        <p className="goal-line muted">
          {loan.matched.count === 0
            ? "Aucune échéance retrouvée dans vos opérations avec ce libellé et ce montant."
            : `${loan.matched.count} échéance${loan.matched.count > 1 ? "s" : ""} retrouvée${loan.matched.count > 1 ? "s" : ""} dans vos opérations${loan.matched.lastDate ? `, la dernière le ${longDate(loan.matched.lastDate)}` : ""}.`}
        </p>
      )}
      <p className="goal-line">
        <button className="link" onClick={toggle} aria-expanded={schedule !== null}>
          {schedule ? "Masquer le tableau d'amortissement" : "Tableau d'amortissement"}
        </button>{" "}
        <button className="link" onClick={onEdit}>
          Modifier
        </button>
      </p>
      {schedule && (
        <div className="table-scroll loan-schedule">
          <table>
            <thead>
              <tr>
                <th>N°</th>
                <th>Date</th>
                <th className="num">Échéance</th>
                <th className="num">Intérêts</th>
                <th className="num">Capital</th>
                <th className="num">Restant dû</th>
              </tr>
            </thead>
            <tbody>
              {schedule.map((i) => (
                <tr key={i.number} className={i.number <= loan.paidInstallments ? "row--muted" : ""}>
                  <td>{i.number}</td>
                  <td>{shortDate(i.date)}</td>
                  <td className="num">{amount(i.paymentCents)}</td>
                  <td className="num">{amount(i.interestCents)}</td>
                  <td className="num">{amount(i.principalCents)}</td>
                  <td className="num">{amount(i.remainingCents)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </li>
  );
}

function LoanForm({ loan, onDone }: { loan?: Loan; onDone: () => void }) {
  const euros = (cents: number | null | undefined) => (cents == null ? "" : (cents / 100).toFixed(2).replace(".", ","));
  const [name, setName] = useState(loan?.name ?? "");
  const [lender, setLender] = useState(loan?.lender ?? "");
  const [principal, setPrincipal] = useState(euros(loan?.principalCents));
  const [rate, setRate] = useState(loan?.annualRate.replace(".", ",") ?? "");
  const [first, setFirst] = useState(loan?.firstPaymentDate ?? "");
  const [term, setTerm] = useState(loan ? String(loan.termMonths) : "");
  const [insurance, setInsurance] = useState(euros(loan?.insuranceMonthlyCents));
  const [label, setLabel] = useState(loan?.paymentLabel ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const principalCents = parseEuros(principal);
  const insuranceCents = parseEuros(insurance);
  const months = /^\d{1,3}$/.test(term.trim()) ? Number(term.trim()) : NaN;
  const valid =
    name.trim().length > 0 &&
    principalCents !== null && Number.isFinite(principalCents) && principalCents > 0 &&
    /^\d{1,2}([.,]\d{1,4})?$/.test(rate.trim()) &&
    first !== "" &&
    Number.isFinite(months) && months >= 1 && months <= 480 &&
    (insuranceCents === null || Number.isFinite(insuranceCents));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    setBusy(true);
    setError(null);
    const body: LoanRequest = {
      name: name.trim(),
      lender: lender.trim() || null,
      principalCents: principalCents!,
      annualRate: rate.trim(),
      firstPaymentDate: first,
      termMonths: months,
      insuranceMonthlyCents: insuranceCents,
      paymentLabel: label.trim() || null,
    };
    try {
      if (loan) await api.updateLoan(loan.id, body);
      else await api.createLoan(body);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Le crédit n'a pas été enregistré.");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!loan) return;
    try {
      await api.deleteLoan(loan.id);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Le crédit n'a pas été supprimé.");
    }
  }

  return (
    <form className="goal-form" onSubmit={submit} aria-label={loan ? `Modifier ${loan.name}` : "Nouveau crédit"}>
      <label className="field">
        <span>Nom</span>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Résidence principale" maxLength={80} />
      </label>
      <label className="field">
        <span>Établissement (facultatif)</span>
        <input value={lender} onChange={(e) => setLender(e.target.value)} maxLength={80} />
      </label>
      <label className="field">
        <span>Montant emprunté</span>
        <input inputMode="decimal" value={principal} onChange={(e) => setPrincipal(e.target.value)} placeholder="200 000" />
      </label>
      <label className="field">
        <span>Taux annuel (%, hors assurance)</span>
        <input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} placeholder="3,25" />
      </label>
      <label className="field">
        <span>Première échéance</span>
        <input type="date" value={first} onChange={(e) => setFirst(e.target.value)} />
      </label>
      <label className="field">
        <span>Durée (mois)</span>
        <input inputMode="numeric" value={term} onChange={(e) => setTerm(e.target.value)} placeholder="240" />
      </label>
      <label className="field">
        <span>Assurance par mois (facultatif)</span>
        <input inputMode="decimal" value={insurance} onChange={(e) => setInsurance(e.target.value)} placeholder="25" />
      </label>
      <label className="field">
        <span>Libellé du prélèvement (facultatif)</span>
        <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="ECHEANCE PRET" maxLength={80} />
      </label>
      {error && <p className="form-error">{error}</p>}
      <div className="goal-form-actions">
        <button type="submit" className="primary" disabled={!valid || busy}>
          {loan ? "Enregistrer" : "Ajouter"}
        </button>
        <button type="button" className="secondary" onClick={onDone}>
          Annuler
        </button>
        {loan && (
          <button type="button" className="link link--danger" onClick={remove}>
            Supprimer le crédit
          </button>
        )}
      </div>
    </form>
  );
}
