// Coque desktop : lance le cœur (image native) en sidecar sur 127.0.0.1,
// attend qu'il écoute, puis affiche l'UI servie par le cœur.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::{SocketAddr, TcpStream};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use tauri::{Manager, RunEvent, Url};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

const CORE_PORT: u16 = 47811;
const STARTUP_ATTEMPTS: u32 = 150; // 150 × 200 ms = 30 s

#[cfg(windows)]
const LIBRARY_ENV: &str = "PATH";
#[cfg(not(windows))]
const LIBRARY_ENV: &str = "LD_LIBRARY_PATH";

struct CoreProcess(Mutex<Option<CommandChild>>);

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
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

            let window = app
                .get_webview_window("main")
                .expect("fenêtre principale absente");
            thread::spawn(move || {
                if wait_for_core() {
                    let url = format!("http://127.0.0.1:{CORE_PORT}/?launch={token}");
                    if let Ok(url) = Url::parse(&url) {
                        let _ = window.navigate(url);
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
                if let Some(child) = app.state::<CoreProcess>().0.lock().unwrap().take() {
                    let _ = child.kill();
                }
            }
        });
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
