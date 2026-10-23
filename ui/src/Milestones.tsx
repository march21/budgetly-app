import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type Milestone, type MilestoneReport } from "./api";
import { amount, longDate } from "./format";

const pct = new Intl.NumberFormat("fr-FR", { style: "percent", maximumFractionDigits: 1 });
const pctWhole = new Intl.NumberFormat("fr-FR", { style: "percent", maximumFractionDigits: 0 });

/** Date du jour à l'heure locale (toISOString est en UTC). */
function localToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function monthsUntil(iso: string): number {
  const now = new Date();
  const d = new Date(`${iso}T12:00:00`);
  return Math.max(0, (d.getFullYear() - now.getFullYear()) * 12 + d.getMonth() - now.getMonth());
}

function until(iso: string): string {
  const months = monthsUntil(iso);
  if (months <= 1) return "dans moins de deux mois";
  if (months < 24) return `dans ${months} mois`;
  return `dans ${Math.floor(months / 12)} ans`;
}

const statusLabel: Record<Milestone["status"], string> = {
  NEEDS_OPENING_DATE: "À compléter",
  UPCOMING: "À venir",
  SOON: "Bientôt",
  REACHED: "Atteint",
};

function text(m: Milestone, rules: MilestoneReport["rules"]): string {
  switch (m.kind) {
    case "PEA_MATURITY":
      if (!m.date) return "Date d'ouverture inconnue : saisissez-la pour connaître la date de ses 5 ans.";
      return m.status === "REACHED"
        ? `5 ans depuis le ${longDate(m.date)} : retraits partiels possibles sans clôturer le plan, gains retirés exonérés d'impôt sur le revenu, prélèvements sociaux dus (${pct.format(rules.socialLeviesGeneral)} en 2026).`
        : `5 ans le ${longDate(m.date)}, ${until(m.date)}. D'ici là, un retrait clôture le plan, sauf exceptions (licenciement, invalidité, création d'entreprise…).`;
    case "LIFE_INSURANCE_MATURITY":
      if (!m.date) return "Date d'ouverture inconnue : saisissez-la pour connaître la date de ses 8 ans.";
      return m.status === "REACHED"
        ? `8 ans depuis le ${longDate(m.date)} : les gains retirés bénéficient de l'abattement annuel de l'assurance vie, puis sont imposés à 7,5 % (12,8 % sur la part des versements au-delà de 150 000 €) ; prélèvements sociaux de ${pct.format(rules.socialLeviesLifeInsurance)} dus.`
        : `8 ans le ${longDate(m.date)}, ${until(m.date)}. Ensuite, les gains retirés bénéficient de l'abattement annuel de l'assurance vie.`;
    case "PEE_RELEASE":
      return `${amount(m.amountCents ?? 0)} (valeur au dernier relevé) disponibles à partir du ${longDate(m.date!)}, ${until(m.date!)}.`;
    case "PEA_CEILING":
      return m.status === "REACHED"
        ? `Plafond des versements atteint (${amount(m.limitCents ?? 0)}) : plus de nouveaux versements possibles.`
        : `Versements : ${amount(m.amountCents ?? 0)} sur un plafond de ${amount(m.limitCents ?? 0)} (${pctWhole.format((m.amountCents ?? 0) / (m.limitCents ?? 1))}).`;
    case "SAVINGS_CEILING":
      return m.status === "REACHED"
        ? `Plafond des versements atteint (${amount(m.limitCents ?? 0)}) : les intérêts continuent de s'ajouter, mais plus les versements.`
        : `Solde : ${amount(m.amountCents ?? 0)} sur un plafond de versements de ${amount(m.limitCents ?? 0)} (${pctWhole.format((m.amountCents ?? 0) / (m.limitCents ?? 1))}).`;
  }
}

export function MilestonesPanel({ refreshKey }: { refreshKey?: number }) {
  const [report, setReport] = useState<MilestoneReport | null>(null);
  const load = () => {
    api.milestones().then(setReport).catch(() => setReport(null));
  };
  useEffect(load, [refreshKey]);

  if (!report || report.milestones.length === 0) return null;
  return (
    <section className="milestones" aria-labelledby="milestones-title">
      <h2 id="milestones-title">Échéances et plafonds</h2>
      <ul>
        {report.milestones.map((m) => (
          <li key={`${m.accountId}-${m.kind}`} className={`milestone milestone--${m.status.toLowerCase()}`}>
            <span className="milestone-status">{statusLabel[m.status]}</span>
            <div>
              <span className="cell-title">{m.accountName}</span>
              <p>{text(m, report.rules)}</p>
              {(m.kind === "PEA_MATURITY" || m.kind === "LIFE_INSURANCE_MATURITY") && (
                <OpeningDateForm milestone={m} onSaved={load} />
              )}
            </div>
          </li>
        ))}
      </ul>
      <p className="muted">
        {report.milestones.some((m) => m.kind === "LIFE_INSURANCE_MATURITY") &&
          `Abattement de l'assurance vie après 8 ans : ${amount(report.rules.lifeInsuranceAllowanceSingleCents)} par an (${amount(report.rules.lifeInsuranceAllowanceCoupleCents)} pour un couple), pour l'ensemble de vos contrats et non par contrat. `}
        Règles vérifiées le {longDate(report.rules.verifiedOn)} (service-public.fr ; assurance vie : courtier Linxea). Repères
        indicatifs, sans valeur de conseil fiscal ; les plafonds des livrets portent sur les versements, pas sur les intérêts.
      </p>
    </section>
  );
}

function OpeningDateForm({ milestone, onSaved }: { milestone: Milestone; onSaved: () => void }) {
  const [open, setOpen] = useState(milestone.status === "NEEDS_OPENING_DATE");
  const [date, setDate] = useState(milestone.openedOn ?? "");
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await api.setOpenedOn(milestone.accountId, date || null);
      setOpen(false);
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La date n'a pas été enregistrée.");
    }
  }

  if (!open) {
    return (
      <button className="link" onClick={() => setOpen(true)}>
        Ouvert le {milestone.openedOn ? longDate(milestone.openedOn) : "?"} · modifier
      </button>
    );
  }
  return (
    <form className="inline-form" onSubmit={submit}>
      <label className="inline-form-label" htmlFor={`opened-${milestone.accountId}`}>
        Date d'ouverture (premier versement)
      </label>
      <input id={`opened-${milestone.accountId}`} type="date" value={date} max={localToday()} onChange={(e) => setDate(e.target.value)} />
      <button type="submit" className="secondary" disabled={!date}>
        Enregistrer
      </button>
      {error && <span className="form-error">{error}</span>}
    </form>
  );
}
