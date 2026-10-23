const euros = new Intl.NumberFormat("fr-FR", {
  style: "currency",
  currency: "EUR",
  minimumFractionDigits: 2,
});

/** Montant signé : « −21,50 € » / « +300,00 € ». Le signe est toujours écrit, la couleur le double. */
export function signedAmount(cents: number): string {
  const formatted = euros.format(Math.abs(cents) / 100);
  if (cents === 0) return formatted;
  return `${cents < 0 ? "−" : "+"}${formatted}`;
}

export function amount(cents: number): string {
  return euros.format(cents / 100);
}

const day = new Intl.DateTimeFormat("fr-FR", { day: "numeric", month: "short" });
const full = new Intl.DateTimeFormat("fr-FR", { day: "numeric", month: "long", year: "numeric" });
const dateTime = new Intl.DateTimeFormat("fr-FR", { dateStyle: "medium", timeStyle: "short" });

const parse = (iso: string) => new Date(`${iso}T12:00:00`);

export const shortDate = (iso: string) => day.format(parse(iso));
export const longDate = (iso: string) => full.format(parse(iso));
export const timestamp = (iso: string) => dateTime.format(new Date(iso));

export const kindLabel: Record<string, string> = {
  CARD: "Carte",
  TRANSFER: "Virement",
  DIRECT_DEBIT: "Prélèvement",
  DEPOSIT: "Versement",
  WITHDRAWAL: "Retrait",
  FEE: "Frais",
  OTHER: "Autre",
};

/** Mois courant au format AAAA-MM, pour le filtre par défaut. */
export function currentMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
}

export function monthBounds(month: string): { from: string; to: string } {
  const [year, m] = month.split("-").map(Number);
  const last = new Date(year, m, 0).getDate();
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, "0")}` };
}

const monthName = new Intl.DateTimeFormat("fr-FR", { month: "long", year: "numeric" });
const monthShort = new Intl.DateTimeFormat("fr-FR", { month: "short" });

/** « octobre 2026 » */
export const monthLabel = (month: string) => monthName.format(new Date(`${month}-15T12:00:00`));
/** « oct. » */
export const monthAbbrev = (month: string) => monthShort.format(new Date(`${month}-15T12:00:00`));

export function shiftMonth(month: string, delta: number): string {
  const [year, m] = month.split("-").map(Number);
  const date = new Date(year, m - 1 + delta, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
}

export const percent = (value: number) =>
  new Intl.NumberFormat("fr-FR", { style: "percent", maximumFractionDigits: 0 }).format(value);

/** « 300 » ou « 300,50 » saisi par l'utilisateur → centimes. */
export function parseEuros(text: string): number | null {
  const cleaned = text.replace(/[\s\u00a0\u202f€]/g, "").replace(",", ".");
  if (cleaned === "") return null;
  // Calcul en entiers (pas de flottant) : euros × 100 + centimes.
  const match = /^\+?(\d+)(?:\.(\d{1,2}))?$/.exec(cleaned);
  if (!match) return NaN;
  return Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
}

export const kindName: Record<string, string> = {
  EXPENSE: "Dépenses",
  INCOME: "Revenus",
  SAVINGS: "Épargne",
  TRANSFER: "Entre mes comptes",
};

/** Libellé d'un support sans ISIN : fonds en euros d'assurance vie, ou placement d'épargne salariale. */
export const noIsinLabel = (kind: string) => (kind === "EURO_FUND" ? "Fonds en euros" : "Sans ISIN · valeur du relevé");
