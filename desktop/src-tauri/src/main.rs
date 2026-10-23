// Coque desktop : lance le cœur (image native) en sidecar sur 127.0.0.1,
// attend qu'il écoute, puis affiche l'UI servie par le cœur.
// Au lancement (réglage désactivable), demande à GitHub s'il existe une version plus récente.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::{SocketAddr, TcpStream};
use std::path::Path;
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use tauri::{AppHandle, Manager, RunEvent, Url};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_updater::UpdaterExt;
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

const CORE_PORT: u16 = 47811;
const STARTUP_ATTEMPTS: u32 = 150; // 150 × 200 ms = 30 s
const RELEASES_URL: &str = "https://github.com/march21/budgetly-app/releases/latest";

#[cfg(windows)]
const LIBRARY_ENV: &str = "PATH";
#[cfg(target_os = "macos")]
const LIBRARY_ENV: &str = "DYLD_LIBRARY_PATH";
#[cfg(not(any(windows, target_os = "macos")))]
const LIBRARY_ENV: &str = "LD_LIBRARY_PATH";

struct CoreProcess(Mutex<Option<CommandChild>>);

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let token = launch_token();
            let data_dir = app.path().app_data_dir()?;
            // Bibliothèques JDK livrées avec l'application (libawt… chargées par PDFBox).
            let lib_dir = app.path().resource_dir()?.join("lib");
            // Sous Windows, le chemin peut porter le préfixe \\?\ que la recherche de DLL par PATH ignore.
            let lib_path = lib_dir.to_string_lossy().trim_start_matches(r"\\?\").to_string();

            let command = app
                .shell()
                .sidecar("budgetly-core")?
                .args([format!("-Djava.library.path={lib_path}")])
                .env(LIBRARY_ENV, prepend_path(&lib_path))
                .env("BUDGETLY_PORT", CORE_PORT.to_string())
                .env("BUDGETLY_LAUNCH_TOKEN", &token)
                .env("BUDGETLY_DATA_DIR", data_dir.to_string_lossy().to_string());

            let (mut events, child) = command.spawn()?;
            app.manage(CoreProcess(Mutex::new(Some(child))));

            tauri::async_runtime::spawn(async move {
                while let Some(event) = events.recv().await {
                    match event {
                        CommandEvent::Stdout(line) | CommandEvent::Stderr(line) => {
                            eprintln!("[core] {}", String::from_utf8_lossy(&line).trim_end());
                        }
                        CommandEvent::Terminated(status) => {
                            eprintln!("[core] arrêté : {:?}", status.code);
                        }
                        _ => {}
                    }
                }
            });

            let check_updates = update_check_enabled(&data_dir);
            let handle = app.handle().clone();
            let window = app
                .get_webview_window("main")
                .expect("fenêtre principale absente");
            thread::spawn(move || {
                if wait_for_core() {
                    let url = format!("http://127.0.0.1:{CORE_PORT}/?launch={token}");
                    if let Ok(url) = Url::parse(&url) {
                        let _ = window.navigate(url);
                    }
                    if check_updates {
                        check_for_update(&handle);
                    }
                } else {
                    let _ = window.eval("document.body.dataset.state = 'failed'");
                }
            });

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("impossible de démarrer Budgetly")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                stop_core(app);
            }
        });
}

fn stop_core(app: &AppHandle) {
    if let Some(child) = app.state::<CoreProcess>().0.lock().unwrap().take() {
        let _ = child.kill();
    }
}

/// Réglage écrit par le cœur (`updates.json`, hors de la base chiffrée). Absent = vérification activée ;
/// présent mais illisible = désactivée (on ne passe jamais outre un refus possible).
fn update_check_enabled(data_dir: &Path) -> bool {
    let file = data_dir.join("updates.json");
    if !file.exists() {
        return true;
    }
    std::fs::read_to_string(&file)
        .ok()
        .and_then(|content| serde_json::from_str::<serde_json::Value>(&content).ok())
        .and_then(|value| value.get("checkAtStartup").and_then(|v| v.as_bool()))
        .unwrap_or(false)
}

/// Une seule requête : `latest.json` de la dernière version publiée sur GitHub. Rien n'est envoyé.
/// L'installeur téléchargé n'est exécuté que si sa signature correspond à la clé publique embarquée.
fn check_for_update(app: &AppHandle) {
    let before_exit = app.clone();
    let updater = match app
        .updater_builder()
        .on_before_exit(move || stop_core(&before_exit))
        .build()
    {
        Ok(updater) => updater,
        Err(_) => return,
    };
    let update = match tauri::async_runtime::block_on(updater.check()) {
        Ok(Some(update)) => update,
        Ok(None) => return,
        Err(e) => {
            eprintln!("[mise à jour] vérification impossible : {}", error_kind(&e));
            return;
        }
    };

    // Windows (NSIS) et macOS (.app) s'installent par l'updater ; le paquet .deb reste installé à la main.
    let installable = cfg!(windows) || cfg!(target_os = "macos");
    let text = if installable {
        format!(
            "Budgetly {} est disponible (version installée : {}).\n\n\
             L'installation prend une minute ; l'application redémarre ensuite. Vos données sont conservées.",
            update.version, update.current_version
        )
    } else {
        format!(
            "Budgetly {} est disponible (version installée : {}).\n\n\
             Téléchargez le paquet .deb et installez-le par-dessus : vos données sont conservées.",
            update.version, update.current_version
        )
    };
    let accept = if installable { "Installer" } else { "Ouvrir la page" };
    let accepted = app
        .dialog()
        .message(text)
        .title("Mise à jour disponible")
        .kind(MessageDialogKind::Info)
        .buttons(MessageDialogButtons::OkCancelCustom(accept.into(), "Plus tard".into()))
        .blocking_show();
    if !accepted {
        return;
    }

    if !installable {
        let _ = app.opener().open_url(RELEASES_URL, None::<&str>);
        return;
    }
    match tauri::async_runtime::block_on(update.download_and_install(|_, _| {}, || {})) {
        Ok(()) => app.restart(),
        Err(e) => {
            eprintln!("[mise à jour] installation impossible : {}", error_kind(&e));
            app.dialog()
                .message("La mise à jour n'a pas pu être installée. Vous pouvez la télécharger depuis la page des versions.")
                .title("Mise à jour")
                .kind(MessageDialogKind::Warning)
                .blocking_show();
        }
    }
}

/// Type d'erreur seulement : les messages peuvent contenir des chemins locaux.
fn error_kind(e: &tauri_plugin_updater::Error) -> String {
    let debug = format!("{e:?}");
    debug.split(['(', ' ', '{']).next().unwrap_or("Erreur").to_string()
}

fn wait_for_core() -> bool {
    let address = SocketAddr::from(([127, 0, 0, 1], CORE_PORT));
    (0..STARTUP_ATTEMPTS).any(|_| {
        if TcpStream::connect_timeout(&address, Duration::from_millis(200)).is_ok() {
            return true;
        }
        thread::sleep(Duration::from_millis(200));
        false
    })
}

/// Ajoute le dossier des bibliothèques en tête de la variable de recherche du système.
fn prepend_path(dir: &str) -> String {
    let separator = if cfg!(windows) { ";" } else { ":" };
    match std::env::var(LIBRARY_ENV) {
        Ok(current) if !current.is_empty() => format!("{dir}{separator}{current}"),
        _ => dir.to_string(),
    }
}

/// Jeton aléatoire de 256 bits, différent à chaque lancement.
fn launch_token() -> String {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes).expect("générateur aléatoire indisponible");
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
