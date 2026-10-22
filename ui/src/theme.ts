/** Thème de l'interface : préférence locale à ce navigateur, « système » par défaut. */
export type ThemeChoice = "system" | "light" | "dark";

const KEY = "budgetly.theme";

export function storedTheme(): ThemeChoice {
  try {
    const value = window.localStorage.getItem(KEY);
    return value === "light" || value === "dark" ? value : "system";
  } catch {
    return "system";
  }
}

export function applyTheme(choice: ThemeChoice) {
  const root = document.documentElement;
  if (choice === "system") delete root.dataset.theme;
  else root.dataset.theme = choice;
}

export function saveTheme(choice: ThemeChoice) {
  applyTheme(choice);
  try {
    if (choice === "system") window.localStorage.removeItem(KEY);
    else window.localStorage.setItem(KEY, choice);
  } catch {
    // Stockage indisponible : le choix vaut pour la session.
  }
}
