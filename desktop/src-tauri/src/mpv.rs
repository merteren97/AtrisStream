use atris_core::{SubtitleAppearance, SubtitleTrack};
use libloading::Library;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    ffi::{c_char, c_double, c_int, c_void, CStr, CString},
    path::PathBuf,
    ptr,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread::{self, JoinHandle},
};
use tauri::{AppHandle, Emitter};

const MPV_FORMAT_FLAG: c_int = 3;
const MPV_FORMAT_INT64: c_int = 4;
const MPV_FORMAT_DOUBLE: c_int = 5;

const MPV_EVENT_NONE: c_int = 0;
const MPV_EVENT_SHUTDOWN: c_int = 1;
const MPV_EVENT_END_FILE: c_int = 7;
const MPV_EVENT_FILE_LOADED: c_int = 8;
const MPV_EVENT_PROPERTY_CHANGE: c_int = 22;

const MPV_END_FILE_REASON_EOF: c_int = 0;
const MPV_END_FILE_REASON_ERROR: c_int = 4;

#[repr(C)]
struct MpvHandle {
    _private: [u8; 0],
}

#[repr(C)]
struct MpvEvent {
    event_id: c_int,
    error: c_int,
    reply_userdata: u64,
    data: *mut c_void,
}

#[repr(C)]
struct MpvEventProperty {
    name: *const c_char,
    format: c_int,
    data: *mut c_void,
}

#[repr(C)]
struct MpvEventEndFile {
    reason: c_int,
    error: c_int,
}

#[derive(Clone, Copy)]
struct MpvHandlePtr(*mut MpvHandle);
unsafe impl Send for MpvHandlePtr {}
unsafe impl Sync for MpvHandlePtr {}

type FnMpvCreate = unsafe extern "C" fn() -> *mut MpvHandle;
type FnMpvSetOptionString =
    unsafe extern "C" fn(*mut MpvHandle, *const c_char, *const c_char) -> c_int;
type FnMpvSetOption =
    unsafe extern "C" fn(*mut MpvHandle, *const c_char, c_int, *mut c_void) -> c_int;
type FnMpvInitialize = unsafe extern "C" fn(*mut MpvHandle) -> c_int;
type FnMpvCommand = unsafe extern "C" fn(*mut MpvHandle, *const *const c_char) -> c_int;
type FnMpvSetPropertyString =
    unsafe extern "C" fn(*mut MpvHandle, *const c_char, *const c_char) -> c_int;
type FnMpvGetProperty =
    unsafe extern "C" fn(*mut MpvHandle, *const c_char, c_int, *mut c_void) -> c_int;
type FnMpvObserveProperty =
    unsafe extern "C" fn(*mut MpvHandle, u64, *const c_char, c_int) -> c_int;
type FnMpvWaitEvent = unsafe extern "C" fn(*mut MpvHandle, c_double) -> *mut MpvEvent;
type FnMpvTerminateDestroy = unsafe extern "C" fn(*mut MpvHandle);

struct MpvApi {
    _lib: Library,
    mpv_create: FnMpvCreate,
    mpv_set_option_string: FnMpvSetOptionString,
    mpv_set_option: FnMpvSetOption,
    mpv_initialize: FnMpvInitialize,
    mpv_command: FnMpvCommand,
    mpv_set_property_string: FnMpvSetPropertyString,
    #[allow(dead_code)]
    mpv_get_property: FnMpvGetProperty,
    mpv_observe_property: FnMpvObserveProperty,
    mpv_wait_event: FnMpvWaitEvent,
    mpv_terminate_destroy: FnMpvTerminateDestroy,
}

unsafe impl Send for MpvApi {}
unsafe impl Sync for MpvApi {}

impl MpvApi {
    fn load(path: &str) -> Result<Self, String> {
        unsafe {
            let lib = Library::new(path)
                .map_err(|e| format!("Failed to load libmpv from '{path}': {e}"))?;

            let mpv_create = *lib
                .get::<FnMpvCreate>(b"mpv_create\0")
                .map_err(|e| format!("Missing mpv_create: {e}"))?;
            let mpv_set_option_string = *lib
                .get::<FnMpvSetOptionString>(b"mpv_set_option_string\0")
                .map_err(|e| format!("Missing mpv_set_option_string: {e}"))?;
            let mpv_set_option = *lib
                .get::<FnMpvSetOption>(b"mpv_set_option\0")
                .map_err(|e| format!("Missing mpv_set_option: {e}"))?;
            let mpv_initialize = *lib
                .get::<FnMpvInitialize>(b"mpv_initialize\0")
                .map_err(|e| format!("Missing mpv_initialize: {e}"))?;
            let mpv_command = *lib
                .get::<FnMpvCommand>(b"mpv_command\0")
                .map_err(|e| format!("Missing mpv_command: {e}"))?;
            let mpv_set_property_string = *lib
                .get::<FnMpvSetPropertyString>(b"mpv_set_property_string\0")
                .map_err(|e| format!("Missing mpv_set_property_string: {e}"))?;
            let mpv_get_property = *lib
                .get::<FnMpvGetProperty>(b"mpv_get_property\0")
                .map_err(|e| format!("Missing mpv_get_property: {e}"))?;
            let mpv_observe_property = *lib
                .get::<FnMpvObserveProperty>(b"mpv_observe_property\0")
                .map_err(|e| format!("Missing mpv_observe_property: {e}"))?;
            let mpv_wait_event = *lib
                .get::<FnMpvWaitEvent>(b"mpv_wait_event\0")
                .map_err(|e| format!("Missing mpv_wait_event: {e}"))?;
            let mpv_terminate_destroy = *lib
                .get::<FnMpvTerminateDestroy>(b"mpv_terminate_destroy\0")
                .map_err(|e| format!("Missing mpv_terminate_destroy: {e}"))?;

            Ok(Self {
                _lib: lib,
                mpv_create,
                mpv_set_option_string,
                mpv_set_option,
                mpv_initialize,
                mpv_command,
                mpv_set_property_string,
                mpv_get_property,
                mpv_observe_property,
                mpv_wait_event,
                mpv_terminate_destroy,
            })
        }
    }

    fn set_option_str(&self, ctx: *mut MpvHandle, name: &str, value: &str) -> Result<(), String> {
        let c_name = CString::new(name).map_err(|e| e.to_string())?;
        let c_val = CString::new(value).map_err(|e| e.to_string())?;
        let rc = unsafe { (self.mpv_set_option_string)(ctx, c_name.as_ptr(), c_val.as_ptr()) };
        if rc < 0 {
            return Err(format!("mpv_set_option_string({name}) failed with {rc}"));
        }
        Ok(())
    }

    fn set_option_i64(&self, ctx: *mut MpvHandle, name: &str, mut value: i64) -> Result<(), String> {
        let c_name = CString::new(name).map_err(|e| e.to_string())?;
        let rc = unsafe {
            (self.mpv_set_option)(
                ctx,
                c_name.as_ptr(),
                MPV_FORMAT_INT64,
                (&mut value as *mut i64).cast::<c_void>(),
            )
        };
        if rc < 0 {
            return Err(format!("mpv_set_option({name}) failed with {rc}"));
        }
        Ok(())
    }

    fn set_property_str(&self, ctx: *mut MpvHandle, name: &str, value: &str) -> Result<(), String> {
        let c_name = CString::new(name).map_err(|e| e.to_string())?;
        let c_val = CString::new(value).map_err(|e| e.to_string())?;
        let rc = unsafe { (self.mpv_set_property_string)(ctx, c_name.as_ptr(), c_val.as_ptr()) };
        if rc < 0 {
            return Err(format!("mpv_set_property_string({name}) failed with {rc}"));
        }
        Ok(())
    }

    fn observe_property(&self, ctx: *mut MpvHandle, name: &str, format: c_int) {
        if let Ok(c_name) = CString::new(name) {
            unsafe {
                (self.mpv_observe_property)(ctx, 0, c_name.as_ptr(), format);
            }
        }
    }

    fn command(&self, ctx: *mut MpvHandle, args: &[&str]) -> Result<(), String> {
        let c_strings: Vec<CString> = args
            .iter()
            .map(|a| CString::new(*a).map_err(|e| e.to_string()))
            .collect::<Result<Vec<_>, _>>()?;
        let mut ptrs: Vec<*const c_char> = c_strings.iter().map(|s| s.as_ptr()).collect();
        ptrs.push(ptr::null());
        let rc = unsafe { (self.mpv_command)(ctx, ptrs.as_ptr()) };
        if rc < 0 {
            return Err(format!("mpv_command({args:?}) failed with {rc}"));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerCapabilities {
    pub libmpv_available: bool,
    pub libmpv_path: Option<String>,
    pub install_hint: Option<String>,
    pub webview_proxy_port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MpvPlaybackState {
    pub active: bool,
    pub paused: bool,
    pub buffering: bool,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub volume: f64,
    pub speed: f64,
    pub eof_reached: bool,
    pub error: Option<String>,
}

impl Default for MpvPlaybackState {
    fn default() -> Self {
        Self {
            active: false,
            paused: false,
            buffering: false,
            position_ms: 0,
            duration_ms: 0,
            volume: 100.0,
            speed: 1.0,
            eof_reached: false,
            error: None,
        }
    }
}

struct ActiveSession {
    api: Arc<MpvApi>,
    handle: MpvHandlePtr,
    stop_flag: Arc<AtomicBool>,
    event_thread: Option<JoinHandle<()>>,
}

pub struct MpvController {
    session: Mutex<Option<ActiveSession>>,
    state: Arc<Mutex<MpvPlaybackState>>,
}

impl Default for MpvController {
    fn default() -> Self {
        Self::new()
    }
}

impl MpvController {
    pub fn new() -> Self {
        Self {
            session: Mutex::new(None),
            state: Arc::new(Mutex::new(MpvPlaybackState::default())),
        }
    }

    pub fn probe(webview_proxy_port: u16) -> PlayerCapabilities {
        if let Some((path, _lib)) = find_and_load_libmpv() {
            PlayerCapabilities {
                libmpv_available: true,
                libmpv_path: Some(path),
                install_hint: None,
                webview_proxy_port,
            }
        } else {
            PlayerCapabilities {
                libmpv_available: false,
                libmpv_path: None,
                install_hint: Some(platform_install_hint().to_string()),
                webview_proxy_port,
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn load(
        &self,
        app_handle: AppHandle,
        window_wid: Option<i64>,
        url: String,
        headers: HashMap<String, String>,
        resume_position_ms: Option<i64>,
        subtitles: Vec<SubtitleTrack>,
        subtitle_appearance: SubtitleAppearance,
        hardware_decoding: bool,
    ) -> Result<(), String> {
        let _ = self.stop();

        let (_path, api) = find_and_load_libmpv().ok_or_else(|| {
            format!(
                "libmpv is not installed. {}",
                platform_install_hint()
            )
        })?;
        let api = Arc::new(api);

        let ctx = unsafe { (api.mpv_create)() };
        if ctx.is_null() {
            return Err("mpv_create() returned null".to_string());
        }

        let init_res = (|| -> Result<(), String> {
            api.set_option_str(
                ctx,
                "hwdec",
                if hardware_decoding { "auto-safe" } else { "no" },
            )?;
            api.set_option_str(ctx, "demuxer-max-bytes", "64MiB")?;
            api.set_option_str(ctx, "cache", "yes")?;
            api.set_option_str(ctx, "keep-open", "no")?;
            api.set_option_str(ctx, "osc", "yes")?;
            api.set_option_str(ctx, "input-default-bindings", "yes")?;
            api.set_option_str(ctx, "input-vo-keyboard", "yes")?;

            if let Some(wid) = window_wid {
                let _ = api.set_option_i64(ctx, "wid", wid);
            } else {
                let _ = api.set_option_str(ctx, "force-window", "yes");
            }

            if !headers.is_empty() {
                let header_list: Vec<String> = headers
                    .iter()
                    .map(|(k, v)| format!("{k}: {v}"))
                    .collect();
                let _ = api.set_option_str(ctx, "http-header-fields", &header_list.join(","));
            }

            if let Some(pos_ms) = resume_position_ms.filter(|&ms| ms > 0) {
                let start_sec = format!("+{:.3}", pos_ms as f64 / 1000.0);
                let _ = api.set_option_str(ctx, "start", &start_sec);
            }

            let font_size = (subtitle_appearance.font_size_sp * 2.2).clamp(18.0, 96.0);
            let _ = api.set_option_str(ctx, "sub-font-size", &format!("{font_size:.0}"));
            let _ = api.set_option_str(
                ctx,
                "sub-color",
                &argb_to_mpv_hex(subtitle_appearance.text_color_argb),
            );
            let _ = api.set_option_str(
                ctx,
                "sub-back-color",
                &argb_to_mpv_hex(subtitle_appearance.background_color_argb),
            );

            let rc = unsafe { (api.mpv_initialize)(ctx) };
            if rc < 0 {
                return Err(format!("mpv_initialize() failed with {rc}"));
            }

            api.observe_property(ctx, "time-pos", MPV_FORMAT_DOUBLE);
            api.observe_property(ctx, "duration", MPV_FORMAT_DOUBLE);
            api.observe_property(ctx, "pause", MPV_FORMAT_FLAG);
            api.observe_property(ctx, "paused-for-cache", MPV_FORMAT_FLAG);
            api.observe_property(ctx, "volume", MPV_FORMAT_DOUBLE);
            api.observe_property(ctx, "speed", MPV_FORMAT_DOUBLE);

            api.command(ctx, &["loadfile", &url, "replace"])?;

            for sub in &subtitles {
                let _ = api.command(
                    ctx,
                    &["sub-add", &sub.url, "auto", &sub.label, &sub.language],
                );
            }

            Ok(())
        })();

        if let Err(err) = init_res {
            unsafe { (api.mpv_terminate_destroy)(ctx) };
            return Err(err);
        }

        if let Ok(mut st) = self.state.lock() {
            *st = MpvPlaybackState {
                active: true,
                paused: false,
                buffering: true,
                position_ms: resume_position_ms.unwrap_or(0),
                duration_ms: 0,
                volume: 100.0,
                speed: 1.0,
                eof_reached: false,
                error: None,
            };
            let _ = app_handle.emit("mpv-state", st.clone());
        }

        let stop_flag = Arc::new(AtomicBool::new(false));
        let thread_stop = stop_flag.clone();
        let thread_api = api.clone();
        let thread_state = self.state.clone();
        let handle_ptr = MpvHandlePtr(ctx);

        let join_handle = thread::spawn(move || {
            let handle_ptr = handle_ptr;
            let ctx_ptr = handle_ptr.0;
            while !thread_stop.load(Ordering::Relaxed) {
                let event_ptr = unsafe { (thread_api.mpv_wait_event)(ctx_ptr, 0.2) };
                if event_ptr.is_null() {
                    continue;
                }
                let ev = unsafe { &*event_ptr };
                match ev.event_id {
                    MPV_EVENT_NONE => {}
                    MPV_EVENT_SHUTDOWN => {
                        if let Ok(mut st) = thread_state.lock() {
                            st.active = false;
                            let _ = app_handle.emit("mpv-state", st.clone());
                        }
                        break;
                    }
                    MPV_EVENT_FILE_LOADED => {
                        if let Ok(mut st) = thread_state.lock() {
                            st.active = true;
                            st.buffering = false;
                            let _ = app_handle.emit("mpv-state", st.clone());
                        }
                    }
                    MPV_EVENT_END_FILE => {
                        let (eof, err_msg) = if !ev.data.is_null() {
                            let end = unsafe { &*ev.data.cast::<MpvEventEndFile>() };
                            let is_eof = end.reason == MPV_END_FILE_REASON_EOF;
                            let err = if end.reason == MPV_END_FILE_REASON_ERROR {
                                Some(format!("mpv playback error (code {})", end.error))
                            } else {
                                None
                            };
                            (is_eof, err)
                        } else {
                            (false, None)
                        };
                        if let Ok(mut st) = thread_state.lock() {
                            st.active = false;
                            st.buffering = false;
                            st.eof_reached = eof;
                            if err_msg.is_some() {
                                st.error = err_msg;
                            }
                            let _ = app_handle.emit("mpv-state", st.clone());
                        }
                    }
                    MPV_EVENT_PROPERTY_CHANGE if !ev.data.is_null() => {
                        let prop = unsafe { &*ev.data.cast::<MpvEventProperty>() };
                        if !prop.name.is_null() && !prop.data.is_null() {
                            let name = unsafe { CStr::from_ptr(prop.name) }.to_string_lossy();
                            let mut changed = false;
                            if let Ok(mut st) = thread_state.lock() {
                                match (name.as_ref(), prop.format) {
                                    ("time-pos", MPV_FORMAT_DOUBLE) => {
                                        let val = unsafe { *prop.data.cast::<f64>() };
                                        let ms = (val * 1000.0).round() as i64;
                                        if (ms - st.position_ms).abs() >= 250 {
                                            st.position_ms = ms.max(0);
                                            changed = true;
                                        }
                                    }
                                    ("duration", MPV_FORMAT_DOUBLE) => {
                                        let val = unsafe { *prop.data.cast::<f64>() };
                                        st.duration_ms = (val * 1000.0).round().max(0.0) as i64;
                                        changed = true;
                                    }
                                    ("pause", MPV_FORMAT_FLAG) => {
                                        let val = unsafe { *prop.data.cast::<c_int>() };
                                        st.paused = val != 0;
                                        changed = true;
                                    }
                                    ("paused-for-cache", MPV_FORMAT_FLAG) => {
                                        let val = unsafe { *prop.data.cast::<c_int>() };
                                        st.buffering = val != 0;
                                        changed = true;
                                    }
                                    ("volume", MPV_FORMAT_DOUBLE) => {
                                        st.volume = unsafe { *prop.data.cast::<f64>() };
                                        changed = true;
                                    }
                                    ("speed", MPV_FORMAT_DOUBLE) => {
                                        st.speed = unsafe { *prop.data.cast::<f64>() };
                                        changed = true;
                                    }
                                    _ => {}
                                }
                                if changed {
                                    let _ = app_handle.emit("mpv-state", st.clone());
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }
        });

        let mut guard = self.session.lock().map_err(|_| "Session lock poisoned")?;
        *guard = Some(ActiveSession {
            api,
            handle: handle_ptr,
            stop_flag,
            event_thread: Some(join_handle),
        });

        Ok(())
    }

    pub fn set_paused(&self, paused: bool) -> Result<(), String> {
        let guard = self.session.lock().map_err(|_| "Session lock poisoned")?;
        let sess = guard.as_ref().ok_or("No active mpv session")?;
        sess.api
            .set_property_str(sess.handle.0, "pause", if paused { "yes" } else { "no" })
    }

    pub fn seek(&self, position_ms: i64) -> Result<(), String> {
        let guard = self.session.lock().map_err(|_| "Session lock poisoned")?;
        let sess = guard.as_ref().ok_or("No active mpv session")?;
        let secs = format!("{:.3}", position_ms.max(0) as f64 / 1000.0);
        sess.api.command(sess.handle.0, &["seek", &secs, "absolute"])
    }

    pub fn set_volume(&self, volume: f64) -> Result<(), String> {
        let guard = self.session.lock().map_err(|_| "Session lock poisoned")?;
        let sess = guard.as_ref().ok_or("No active mpv session")?;
        let clamped = volume.clamp(0.0, 100.0);
        sess.api
            .set_property_str(sess.handle.0, "volume", &format!("{clamped:.1}"))
    }

    pub fn set_speed(&self, speed: f64) -> Result<(), String> {
        let guard = self.session.lock().map_err(|_| "Session lock poisoned")?;
        let sess = guard.as_ref().ok_or("No active mpv session")?;
        let clamped = speed.clamp(0.25, 4.0);
        sess.api
            .set_property_str(sess.handle.0, "speed", &format!("{clamped:.2}"))
    }

    pub fn select_subtitle(&self, track_id: Option<String>) -> Result<(), String> {
        let guard = self.session.lock().map_err(|_| "Session lock poisoned")?;
        let sess = guard.as_ref().ok_or("No active mpv session")?;
        let val = track_id.unwrap_or_else(|| "no".to_string());
        sess.api.set_property_str(sess.handle.0, "sid", &val)
    }

    pub fn add_subtitle(&self, url: &str) -> Result<(), String> {
        let guard = self.session.lock().map_err(|_| "Session lock poisoned")?;
        let sess = guard.as_ref().ok_or("No active mpv session")?;
        sess.api.command(sess.handle.0, &["sub-add", url, "select"])
    }

    pub fn stop(&self) -> Result<(), String> {
        let mut sess_opt = {
            let mut guard = self.session.lock().map_err(|_| "Session lock poisoned")?;
            guard.take()
        };

        if let Some(ref mut sess) = sess_opt {
            sess.stop_flag.store(true, Ordering::Relaxed);
            let _ = sess.api.command(sess.handle.0, &["quit"]);
            if let Some(jh) = sess.event_thread.take() {
                let _ = jh.join();
            }
            unsafe {
                (sess.api.mpv_terminate_destroy)(sess.handle.0);
            }
        }

        if let Ok(mut st) = self.state.lock() {
            st.active = false;
            st.buffering = false;
        }
        Ok(())
    }

    pub fn get_state(&self) -> MpvPlaybackState {
        self.state
            .lock()
            .map(|s| s.clone())
            .unwrap_or_default()
    }
}

impl Drop for MpvController {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

fn candidate_lib_paths() -> Vec<String> {
    let mut paths = Vec::new();
    let exe_dir: Option<PathBuf> = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()));

    #[cfg(target_os = "windows")]
    let names = ["lib/libmpv-2.dll", "libmpv-2.dll", "mpv-2.dll"];
    #[cfg(target_os = "macos")]
    let names = [
        "lib/libmpv.2.dylib",
        "/opt/homebrew/lib/libmpv.2.dylib",
        "/usr/local/lib/libmpv.2.dylib",
        "libmpv.2.dylib",
    ];
    #[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
    let names = ["lib/libmpv.so.2", "libmpv.so.2", "libmpv.so"];

    if let Some(dir) = exe_dir {
        for name in &names {
            if !name.starts_with('/') {
                let joined = dir.join(name);
                if joined.exists() {
                    paths.push(joined.to_string_lossy().to_string());
                }
            }
        }
    }
    for name in names {
        paths.push(name.to_string());
    }
    paths
}

fn find_and_load_libmpv() -> Option<(String, MpvApi)> {
    for candidate in candidate_lib_paths() {
        if let Ok(api) = MpvApi::load(&candidate) {
            return Some((candidate, api));
        }
    }
    None
}

fn platform_install_hint() -> &'static str {
    #[cfg(target_os = "windows")]
    {
        "Run desktop/scripts/setup-libmpv.ps1 or place libmpv-2.dll in desktop/src-tauri/lib/."
    }
    #[cfg(target_os = "macos")]
    {
        "Install with `brew install mpv` (or run desktop/scripts/setup-libmpv.sh)."
    }
    #[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
    {
        "Install libmpv2 (`sudo apt install libmpv-dev` or `libmpv2`) to enable native playback."
    }
}

fn argb_to_mpv_hex(argb: i64) -> String {
    let val = argb as u32;
    format!("#{val:08X}")
}
