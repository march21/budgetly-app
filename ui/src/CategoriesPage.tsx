import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type Category, type CategoryKind, type Rule, type RuleSuggestion } from "./api";
import { Amount } from "./Amount";
import { CategoryOptions } from "./CategoryPicker";
import { kindName, parseEuros, shortDate } from "./format";

type Props = { categories: Category[]; rules: Rule[]; onChanged: () => void };

export function CategoriesPage({ categories, rules, onChanged }: Props) {
  const [error, setError] = useState<string | null>(null);

  async function run(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La modification n'a pas été enregistrée.");
    }
  }

  return (
    <section className="page">
      <header className="page-header">
        <h1>Catégories et règles</h1>
        <p className="muted">
          Les règles classent automatiquement les opérations dont le libellé contient un motif. Un classement fait à la main
          n'est jamais modifié par une règle.
        </p>
      </header>

      {error && (
        <div className="notice notice--error" role="alert">
          <p>{error}</p>
        </div>
      )}

      <SuggestionsSection categories={categories} rules={rules} run={run} />

      {(["EXPENSE", "INCOME", "SAVINGS"] as CategoryKind[]).map((kind) => (
        <CategoryGroup key={kind} kind={kind} categories={categories} run={run} />
      ))}

      <section className="settings-section">
        <h2>Règles</h2>
        <NewRuleForm categories={categories} run={run} />
        <table>
          <thead>
            <tr>
              <th>Le libellé contient</th>
              <th>Catégorie</th>
              <th>Origine</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rules.map((rule) => (
              <tr key={rule.id}>
                <td className="rule-pattern">{rule.pattern}</td>
                <td>{rule.categoryName}</td>
                <td className="muted">{rule.priority <= 20 ? "Vous" : "Par défaut"}</td>
                <td className="num">
                  <button className="link" onClick={() => run(() => api.deleteRule(rule.id))}>
                    Supprimer
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </section>
  );
}

function CategoryGroup({
  kind,
  categories,
  run,
}: {
  kind: CategoryKind;
  categories: Category[];
  run: (action: () => Promise<unknown>) => Promise<void>;
}) {
  const tops = categories.filter((c) => c.kind === kind && c.parentId === null);
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");

  function add(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return;
    run(() => api.createCategory({ name, parentId: parentId ? Number(parentId) : null, kind })).then(() => setName(""));
  }

  return (
    <section className="settings-section">
      <h2>{kindName[kind]}</h2>
      <table>
        <thead>
          <tr>
            <th>Catégorie</th>
            <th className="num">Opérations</th>
            {kind === "EXPENSE" && <th className="num">Budget mensuel</th>}
            <th />
          </tr>
        </thead>
        <tbody>
          {tops.flatMap((top) => [top, ...categories.filter((c) => c.parentId === top.id)]).map((category) => (
            <tr key={category.id}>
              <td className={category.parentId ? "category-child" : "category-top"}>{category.name}</td>
              <td className="num">{category.transactionCount}</td>
              {kind === "EXPENSE" && (
                <td className="num">
                  <BudgetInput category={category} run={run} />
                </td>
              )}
              <td className="num">
                {!category.system && (
                  <button
                    className="link"
                    onClick={() => {
                      if (window.confirm(`Supprimer « ${category.name} » ? Ses opérations seront reclassées automatiquement.`)) {
                        run(() => api.deleteCategory(category.id));
                      }
                    }}
                  >
                    Supprimer
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <form className="inline-form" onSubmit={add}>
        <input placeholder="Nouvelle catégorie" value={name} onChange={(e) => setName(e.target.value)} aria-label="Nom de la catégorie" />
        <select value={parentId} onChange={(e) => setParentId(e.target.value)} aria-label="Catégorie parente">
          <option value="">Catégorie principale</option>
          {tops.map((top) => (
            <option key={top.id} value={top.id}>
              Dans {top.name}
            </option>
          ))}
        </select>
        <button className="secondary" type="submit" disabled={!name.trim()}>
          Ajouter
        </button>
      </form>
    </section>
  );
}

function BudgetInput({ category, run }: { category: Category; run: (action: () => Promise<unknown>) => Promise<void> }) {
  const initial = category.budgetCents === null ? "" : String(category.budgetCents / 100).replace(".", ",");
  const [value, setValue] = useState(initial);
  const invalid = Number.isNaN(parseEuros(value));

  return (
    <input
      className={`budget-input ${invalid ? "budget-input--invalid" : ""}`}
      inputMode="decimal"
      placeholder="—"
      value={value}
      aria-label={`Budget mensuel ${category.name}`}
      onChange={(e) => setValue(e.target.value)}
      onBlur={() => {
        const cents = parseEuros(value);
        if (value === initial || Number.isNaN(cents)) return;
        run(() => api.setBudget(category.id, cents));
      }}
    />
  );
}

function NewRuleForm({ categories, run }: { categories: Category[]; run: (action: () => Promise<unknown>) => Promise<void> }) {
  const [pattern, setPattern] = useState("");
  const [categoryId, setCategoryId] = useState("");

  return (
    <form
      className="inline-form"
      onSubmit={(e) => {
        e.preventDefault();
        run(() => api.createRule(pattern, Number(categoryId))).then(() => setPattern(""));
      }}
    >
      <input placeholder="Motif, ex. BOULANGERIE" value={pattern} onChange={(e) => setPattern(e.target.value)} aria-label="Motif" />
      <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} aria-label="Catégorie">
        <option value="" disabled>
          Catégorie
        </option>
        <CategoryOptions categories={categories} />
      </select>
      <button className="secondary" type="submit" disabled={pattern.trim().length < 3 || !categoryId}>
        Ajouter la règle
      </button>
    </form>
  );
}

/** Libellés non classés les plus fréquents : une règle en un clic pour chacun. */
function SuggestionsSection({
  categories,
  rules,
  run,
}: {
  categories: Category[];
  rules: Rule[];
  run: (action: () => Promise<unknown>) => Promise<void>;
}) {
  const [suggestions, setSuggestions] = useState<RuleSuggestion[] | null>(null);
  const [choice, setChoice] = useState<Record<string, string>>({});

  // Rechargé à chaque changement de règles (une règle créée retire son motif de la liste).
  useEffect(() => {
    api.ruleSuggestions().then(setSuggestions).catch(() => setSuggestions(null));
  }, [rules]);

  if (!suggestions || suggestions.length === 0) return null;
  const total = suggestions.reduce((sum, s) => sum + s.count, 0);

  return (
    <section className="settings-section suggestions">
      <h2>Règles suggérées</h2>
      <p className="muted">
        Les libellés qui reviennent le plus parmi les opérations à classer ({total} opérations). Choisissez une catégorie :
        la règle classe aussitôt les opérations passées et futures.
      </p>
      <table>
        <thead>
          <tr>
            <th>Le libellé contient</th>
            <th className="num">Opérations</th>
            <th className="num">Total</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {suggestions.map((s) => (
            <tr key={s.pattern}>
              <td>
                <span className="cell-title suggestion-pattern">{s.pattern}</span>
                <span className="cell-meta">
                  {s.sampleLabel} · dernière le {shortDate(s.lastDate)}
                </span>
              </td>
              <td className="num">{s.count}</td>
              <td className="num">
                <Amount cents={s.totalCents} signed />
              </td>
              <td>
                <div className="suggestion-actions">
                  <select
                    value={choice[s.pattern] ?? ""}
                    onChange={(e) => setChoice({ ...choice, [s.pattern]: e.target.value })}
                    aria-label={`Catégorie pour ${s.pattern}`}
                  >
                    <option value="">Catégorie…</option>
                    <CategoryOptions categories={categories.filter((c) => !c.system)} />
                  </select>
                  <button
                    className="secondary"
                    disabled={!choice[s.pattern]}
                    onClick={() => run(() => api.createRule(s.pattern, Number(choice[s.pattern])))}
                  >
                    Créer la règle
                  </button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
