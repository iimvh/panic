//! The `web` plugin's JSON + SSE API (plugins/web/README.md), behind [`Backend`] so a later Rust core can
//! answer the same calls without the views changing.

mod http;

pub use http::HttpBackend;

use crate::core::conversations::{ConversationInfo, Workspaces};
use crate::core::reply::HistoryMessage;
use crate::core::sse::SseEvent;
use crate::core::trace::Span;
use serde::Deserialize;
use std::fmt;

/// A failed call. `status` 0: the server could not be reached.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiError {
    pub status: u16,
    pub message: String,
}

impl ApiError {
    pub fn network() -> Self {
        ApiError { status: 0, message: "Cannot reach the Panic server.".into() }
    }
    pub fn is_network(&self) -> bool {
        self.status == 0
    }
}

impl fmt::Display for ApiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

pub type Result<T> = std::result::Result<T, ApiError>;

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Default)]
pub struct ProviderStatus {
    pub id: String,
    #[serde(default)]
    pub connected: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Default)]
pub struct CommandInfo {
    pub name: String,
    #[serde(default)]
    pub usage: String,
    #[serde(default)]
    pub description: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct State {
    pub provider: Option<String>,
    pub model: Option<String>,
    /// None when off.
    pub reasoning_effort: Option<String>,
    #[serde(default)]
    pub connected: bool,
    pub effort_supported: Option<bool>,
    #[serde(default)]
    pub providers: Vec<ProviderStatus>,
    #[serde(default)]
    pub commands: Vec<CommandInfo>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Model {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub context_length: u64,
    #[serde(default)]
    pub max_output: u64,
    pub free: Option<bool>,
    pub reasoning: Option<bool>,
}

/// A per-chat switch and what the config says for every chat: `{bypass, default}` or `{plan, default}`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Toggle {
    pub on: bool,
    pub default: bool,
}

/// What `PUT /api/settings` changes; `reasoning_effort` is a level or "off".
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SettingsChange {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub reasoning_effort: Option<String>,
}

/// One answer per question, in order: the labels picked and the user's own text.
pub type Answers = Vec<(Vec<String>, String)>;

/// The events of a stream as they arrive; ends after the last one. An `Err` means the connection broke.
pub type Events = Box<dyn Iterator<Item = Result<SseEvent>> + Send>;

pub trait Backend: Send + Sync {
    fn state(&self) -> Result<State>;
    fn history(&self, session: &str) -> Result<Vec<HistoryMessage>>;
    fn conversations(&self) -> Result<Vec<ConversationInfo>>;
    /// 409 while an answer runs in it; 404 counts as done.
    fn delete_conversation(&self, session: &str) -> Result<()>;
    /// None: no `workspace` plugin.
    fn workspaces(&self) -> Result<Option<Workspaces>>;
    /// None: no `permissions` plugin.
    fn permissions(&self, session: &str) -> Result<Option<Toggle>>;
    /// None: no `planner` plugin.
    fn mode(&self, session: &str) -> Result<Option<Toggle>>;
    fn trace(&self, session: &str, id: &str) -> Result<Vec<Span>>;
    fn models(&self, filter: &str) -> Result<Vec<Model>>;
    fn put_settings(&self, change: &SettingsChange) -> Result<State>;
    fn approve(&self, session: &str, id: &str, allow: bool) -> Result<()>;
    fn plan(&self, session: &str, id: &str, approve: bool, feedback: &str) -> Result<()>;
    fn answer(&self, session: &str, id: &str, answers: &Answers) -> Result<()>;
    fn stop(&self, session: &str) -> Result<()>;
    fn chat(&self, session: &str, text: &str) -> Result<Events>;
    fn command(&self, session: &str, line: &str) -> Result<Events>;
    /// The session's answer in flight after SSE id `after`, then live; None when none runs.
    fn follow(&self, session: &str, after: u64) -> Result<Option<Events>>;
}

/// The argument of a command line: quoted so a path with spaces stays one argument.
pub fn quote_arg(arg: &str) -> String {
    if arg.contains('"') { format!("'{arg}'") } else { format!("\"{arg}\"") }
}
