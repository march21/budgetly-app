import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type BackupKind, type BackupList } from "./api";
import { timestamp } from "./format";
import { saveTheme, storedTheme, type ThemeChoice } from "./theme";

const kindLabel: Record<BackupKind, string> = {
  MANUAL: "Manuelle",
  AUTOMATIC: "Automatique",
  SAFETY: "Avant changement de mot de passe",
};

const size = (bytes: number) =>
  bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} Ko` : `${(bytes / 1024 / 1024).toFixed(1).replace(".", ",")} Mo`;

export function SettingsPage({ minPasswordLength }: { minPasswordLength: number }) {
  return (
    <section className="page">
      <header className="page-header">
        <h1>Réglages</h1>
      </header>
      <ThemeSection />
      <BackupSection />
      <AutoLockSection />
      <PasswordSection minPasswordLength={minPasswordLength} />
      <ExportSection />
    </section>
  );
}

function ThemeSection() {
  const [theme, setTheme] = useState<ThemeChoice>(storedTheme());
  const choices: { value: ThemeChoice; label: string }[] = [
    { value: "system", label: "Comme le système" },
    { value: "light", label: "Clair" },
    { value: "dark", label: "Sombre" },
  ];
  return (
    <section className="settings-block" aria-labelledby="theme-title">
      <h2 id="theme-title">Apparence</h2>
      <div className="segmented" role="radiogroup" aria-labelledby="theme-title">
        {choices.map((c) => (
          <button
            key={c.value}
            role="radio"
            aria-checked={theme === c.value}
            className={theme === c.value ? "active" : ""}
            onClick={() => {
              setTheme(c.value);
              saveTheme(c.value);
            }}
          >
            {c.label}
          </button>
        ))}
      </div>
    </section>
  );
}

function BackupSection() {
  const [list, setList] = useState<BackupList | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);

  const load = () => {
    api.backups().then(setList).catch(() => setList(null));
  };
  useEffect(load, []);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      await api.createBackup();
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La sauvegarde a échoué.");
    } finally {
      setBusy(false);
    }
  }

  async function remove(name: string) {
    setConfirming(null);
    try {
      await api.deleteBackup(name);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La sauvegarde n'a pas été supprimée.");
    }
  }

  return (
    <section className="settings-block" aria-labelledby="backup-title">
      <div className="settings-block-header">
        <div>
          <h2 id="backup-title">Sauvegardes</h2>
          <p className="muted">
            Une sauvegarde est faite automatiquement chaque jour à l'ouverture du coffre ; les sept dernières sont
            gardées. Elle reste chiffrée : il faut le mot de passe maître de l'époque pour la restaurer, depuis l'écran
            de déverrouillage.
          </p>
        </div>
        <button className="secondary" onClick={create} disabled={busy}>
          {busy ? "Sauvegarde…" : "Sauvegarder maintenant"}
        </button>
      </div>
      {error && <p className="form-error">{error}</p>}
      {list && (
        <>
          <p className="muted settings-path">
            Dossier : <code>{list.directory}</code>. Copiez régulièrement une sauvegarde sur un autre support.
          </p>
          {list.backups.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Type</th>
                  <th className="num">Taille</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {list.backups.map((b) => (
                  <tr key={b.name}>
                    <td>{timestamp(b.createdAt)}</td>
                    <td className="muted">{kindLabel[b.kind]}</td>
                    <td className="num">{size(b.sizeBytes)}</td>
                    <td className="row-actions">
                      <a className="link" href={api.backupUrl(b.name)} download={b.name}>
                        Télécharger
                      </a>
                      {confirming === b.name ? (
                        <>
                          <button className="link link--danger" onClick={() => remove(b.name)}>
                            Confirmer la suppression
                          </button>
                          <button className="link" onClick={() => setConfirming(null)}>
                            Garder
                          </button>
                        </>
                      ) : (
                        <button className="link" onClick={() => setConfirming(b.name)}>
                          Supprimer
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}

function AutoLockSection() {
  const [minutes, setMinutes] = useState<number | null>(null);
  const [allowed, setAllowed] = useState<number[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .autoLock()
      .then((s) => {
        setMinutes(s.minutes);
        setAllowed(s.allowed);
      })
      .catch(() => undefined);
  }, []);

  async function choose(value: number) {
    setError(null);
    try {
      setMinutes((await api.setAutoLock(value)).minutes);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Le réglage n'a pas été enregistré.");
    }
  }

  if (minutes === null) return null;
  const ordered = [...allowed.filter((m) => m > 0), ...allowed.filter((m) => m === 0)];
  return (
    <section className="settings-block" aria-labelledby="autolock-title">
      <h2 id="autolock-title">Verrouillage automatique</h2>
      <p className="muted">Le coffre se referme après ce délai sans activité ; il faut alors ressaisir le mot de passe maître.</p>
      <div className="segmented" role="radiogroup" aria-labelledby="autolock-title">
        {ordered.map((m) => (
          <button key={m} role="radio" aria-checked={minutes === m} className={minutes === m ? "active" : ""} onClick={() => choose(m)}>
            {m === 0 ? "Jamais" : m === 60 ? "1 h" : `${m} min`}
          </button>
        ))}
      </div>
      {error && <p className="form-error">{error}</p>}
    </section>
  );
}

function PasswordSection({ minPasswordLength }: { minPasswordLength: number }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const mismatch = confirmation.length > 0 && confirmation !== next;
  const tooShort = next.length > 0 && next.length < minPasswordLength;
  const canSubmit = !busy && current.length > 0 && next.length >= minPasswordLength && confirmation === next;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    setDone(false);
    try {
      await api.changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirmation("");
      setDone(true);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Le mot de passe n'a pas été changé.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="settings-block" aria-labelledby="password-title">
      <h2 id="password-title">Mot de passe maître</h2>
      <p className="muted">
        La base est rechiffrée avec le nouveau mot de passe. Une sauvegarde est faite juste avant : elle s'ouvrira avec
        l'ancien.
      </p>
      <form className="password-form" onSubmit={submit}>
        <label className="field">
          <span>Mot de passe actuel</span>
          <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </label>
        <label className="field">
          <span>Nouveau mot de passe</span>
          <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
        </label>
        <label className="field">
          <span>Confirmation</span>
          <input type="password" autoComplete="new-password" value={confirmation} onChange={(e) => setConfirmation(e.target.value)} />
        </label>
        {tooShort && <p className="hint hint--warn">Au moins {minPasswordLength} caractères.</p>}
        {mismatch && <p className="hint hint--warn">Les deux saisies sont différentes.</p>}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        {done && (
          <p className="check check--ok" role="status">
            Mot de passe changé.
          </p>
        )}
        <button className="secondary" type="submit" disabled={!canSubmit}>
          {busy ? "Rechiffrement…" : "Changer le mot de passe"}
        </button>
      </form>
    </section>
  );
}

function ExportSection() {
  return (
    <section className="settings-block" aria-labelledby="export-title">
      <h2 id="export-title">Export</h2>
      <p className="muted">
        Toutes les opérations au format CSV, lisible par Excel ou LibreOffice. Le fichier n'est pas chiffré : rangez-le
        en conséquence.
      </p>
      <a className="secondary button-link" href={api.csvUrl} download>
        Exporter les opérations (CSV)
      </a>
    </section>
  );
}
