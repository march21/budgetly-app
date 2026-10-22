import { useState, type FormEvent } from "react";
import { api, ApiError, type VaultStatus } from "./api";

export function VaultScreen({ status, onUnlocked }: { status: VaultStatus; onUnlocked: () => void }) {
  const creating = status.state === "UNINITIALIZED";
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(false);

  const tooShort = password.length > 0 && password.length < status.minPasswordLength;
  const mismatch = creating && confirmation.length > 0 && confirmation !== password;
  const canSubmit =
    !busy && password.length >= (creating ? status.minPasswordLength : 1) && (!creating || confirmation === password);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      await (creating ? api.setup(password) : api.unlock(password));
      setPassword("");
      setConfirmation("");
      onUnlocked();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Le cœur ne répond pas.");
    } finally {
      setBusy(false);
    }
  }

  if (restoring) return <RestoreScreen onCancel={() => setRestoring(false)} onRestored={onUnlocked} />;

  return (
    <main className="vault">
      <form className="vault-form" onSubmit={submit}>
        <div className="brand brand--large">Budgetly</div>
        {creating ? (
          <>
            <h1>Créez votre mot de passe maître</h1>
            <p className="muted">
              Il chiffre toutes vos données sur cet ordinateur. Il n'est enregistré nulle part : s'il est perdu, les
              données ne peuvent pas être récupérées.
            </p>
          </>
        ) : (
          <>
            <h1>Déverrouillez vos comptes</h1>
            <p className="muted">Saisissez votre mot de passe maître.</p>
          </>
        )}

        <label className="field">
          <span>Mot de passe maître</span>
          <input
            type="password"
            autoFocus
            autoComplete={creating ? "new-password" : "current-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-describedby="password-help"
          />
        </label>
        {creating && (
          <p id="password-help" className={`hint ${tooShort ? "hint--warn" : ""}`}>
            Au moins {status.minPasswordLength} caractères. Une phrase de plusieurs mots est plus simple à retenir.
          </p>
        )}

        {creating && (
          <label className="field">
            <span>Confirmation</span>
            <input
              type="password"
              autoComplete="new-password"
              value={confirmation}
              onChange={(e) => setConfirmation(e.target.value)}
            />
          </label>
        )}
        {mismatch && <p className="hint hint--warn">Les deux saisies sont différentes.</p>}

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <button className="primary" type="submit" disabled={!canSubmit}>
          {busy ? "Ouverture…" : creating ? "Créer le coffre" : "Déverrouiller"}
        </button>

        <button type="button" className="link vault-alt" onClick={() => setRestoring(true)}>
          Restaurer une sauvegarde
        </button>
      </form>
    </main>
  );
}

function RestoreScreen({ onCancel, onRestored }: { onCancel: () => void; onRestored: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!file || !password || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api.restore(file, password);
      setPassword("");
      onRestored();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Le cœur ne répond pas.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="vault">
      <form className="vault-form" onSubmit={submit}>
        <div className="brand brand--large">Budgetly</div>
        <h1>Restaurer une sauvegarde</h1>
        <p className="muted">
          Choisissez un fichier .budgetly et le mot de passe maître en vigueur quand il a été créé. Les données actuelles
          de cet ordinateur sont mises de côté dans le dossier de Budgetly, pas effacées.
        </p>
        <label className="field">
          <span>Fichier de sauvegarde</span>
          <input type="file" accept=".budgetly" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </label>
        <label className="field">
          <span>Mot de passe de la sauvegarde</span>
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <button className="primary" type="submit" disabled={!file || !password || busy}>
          {busy ? "Restauration…" : "Restaurer et ouvrir"}
        </button>
        <button type="button" className="link vault-alt" onClick={onCancel} disabled={busy}>
          Retour
        </button>
      </form>
    </main>
  );
}
