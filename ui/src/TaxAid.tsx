import { useEffect, useState } from "react";
import { api, type TaxAidReport, type TaxItem } from "./api";
import { amount, longDate } from "./format";

type Line = { item: TaxItem; title: string; text: string };

function lines(r: TaxAidReport): Line[] {
  const rules = r.rules;
  const all: Line[] = [
    {
      item: r.donationsHelp,
      title: "Dons aux personnes en difficulté",
      text: `Réduction de ${rules.donationHelpRatePercent} % dans la limite de ${amount(rules.donationHelpCeilingCents)} de dons (au-delà, ${rules.donationRatePercent} %).`,
    },
    {
      item: r.donations,
      title: "Dons aux associations",
      text: `Réduction de ${rules.donationRatePercent} %, dans la limite de 20 % du revenu imposable. Gardez les reçus fiscaux.`,
    },
    {
      item: r.homeServices,
      title: "Emploi à domicile",
      text: `Crédit d'impôt de ${rules.homeServicesRatePercent} % des dépenses, ${amount(rules.homeServicesCeilingCents)} de dépenses au plus (majorations possibles). Aides reçues à déduire ; vérifiez l'avance immédiate déjà perçue.`,
    },
    {
      item: r.childcare,
      title: "Garde d'enfants de moins de 6 ans hors du domicile",
      text: `Crédit d'impôt de ${rules.childcareRatePercent} %, ${amount(rules.childcareCeilingPerChildCents)} de dépenses par enfant au plus, aides de la CAF déduites.`,
    },
    {
      item: r.retirementSavings,
      title: "Versements sur un PER",
      text: "Déductibles du revenu dans la limite du plafond indiqué sur votre dernier avis d'imposition.",
    },
    {
      item: r.taxableInterest,
      title: "Intérêts imposables",
      text: "Comptes et livrets non réglementés : en général déjà soumis au prélèvement à la source par la banque ; reportez le montant de l'imprimé fiscal unique (IFU).",
    },
    {
      item: r.regulatedInterest,
      title: "Intérêts du Livret A et du LDDS",
      text: "Exonérés d'impôt et de prélèvements sociaux : rien à déclarer.",
    },
    { item: r.taxesPaid, title: "Impôts et taxes payés", text: "Pour mémoire : prélèvements catégorisés « Impôts et taxes »." },
  ];
  return all.filter((l) => l.item.transactionCount > 0);
}

/** Aide à la déclaration des revenus d'une année, d'après les opérations catégorisées. */
export function TaxAidPanel({ year }: { year: number }) {
  const [report, setReport] = useState<TaxAidReport | null>(null);
  useEffect(() => {
    api.taxAid(year).then(setReport).catch(() => setReport(null));
  }, [year]);
  if (!report) return null;
  const shown = lines(report);
  return (
    <section className="settings-section tax-aid" aria-labelledby="tax-aid-title">
      <h2 id="tax-aid-title">Aide à la déclaration des revenus {year}</h2>
      {shown.length === 0 ? (
        <p className="muted">
          Rien à signaler pour {year}. Classez vos dons, emploi à domicile, frais de garde et versements PER dans leurs
          catégories (« Dons », « Emploi à domicile », « Garde d'enfants hors du domicile », « Épargne retraite (PER) »)
          pour les retrouver ici.
        </p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Poste</th>
                <th className="num">Montant {year}</th>
                <th className="num">Avantage estimé</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((l) => (
                <tr key={l.item.code}>
                  <td>
                    <span className="cell-title">{l.title}</span>
                    <span className="cell-meta">{l.text}</span>
                  </td>
                  <td className="num">{amount(l.item.cents)}</td>
                  <td className="num">{l.item.estimatedBenefitCents === null ? "—" : amount(l.item.estimatedBenefitCents)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted">
        D'après les opérations catégorisées, au taux légal, sans les plafonds propres à votre foyer. Règles vérifiées le{" "}
        {longDate(report.rules.verifiedOn)} (service-public.fr et loi de finances 2026). Les attestations et l'imprimé
        fiscal unique de vos établissements font foi ; ceci n'est pas un conseil fiscal.
      </p>
    </section>
  );
}
