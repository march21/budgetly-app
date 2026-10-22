import { amount, signedAmount } from "./format";

/** Montant en chiffres tabulaires. `signed` écrit toujours le signe ; la couleur ne fait que le doubler. */
export function Amount({ cents, signed = false, className = "" }: { cents: number; signed?: boolean; className?: string }) {
  const tone = cents < 0 ? "debit" : cents > 0 ? "credit" : "zero";
  return (
    <span className={`amount amount--${signed ? tone : "plain"} ${className}`}>
      {signed ? signedAmount(cents) : amount(cents)}
    </span>
  );
}
