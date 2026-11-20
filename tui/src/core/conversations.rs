//! Conversations and projects as the chat switcher lists them, ported from ui-v2's `conversations.ts`.

use serde::Deserialize;

/// A conversation as `GET /api/conversations` lists it; `running`: its answer runs now.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConversationInfo {
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub updated_at: i64,
    #[serde(default)]
    pub running: bool,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Project {
    pub path: String,
    pub name: String,
    #[serde(default)]
    pub conversations: Vec<String>,
}

/// `hidden`: chats of removed projects, which the list does not show.
#[derive(Debug, Clone, PartialEq, Deserialize, Default)]
pub struct Workspaces {
    #[serde(default)]
    pub projects: Vec<Project>,
    #[serde(default)]
    pub hidden: Vec<String>,
}

impl Workspaces {
    /// The project a conversation works in.
    pub fn project_of(&self, id: &str) -> Option<&Project> {
        self.projects.iter().find(|p| p.conversations.iter().any(|c| c == id))
    }

    /// The project at `dir` (the folder the terminal runs in), compared without a trailing separator.
    pub fn project_at(&self, dir: &str) -> Option<&Project> {
        let norm = |p: &str| p.trim_end_matches(['/', '\\']).to_string();
        let d = norm(dir);
        self.projects.iter().find(|p| norm(&p.path) == d)
    }
}

pub fn without(list: &[ConversationInfo], id: &str) -> Vec<ConversationInfo> {
    list.iter().filter(|c| c.id != id).cloned().collect()
}

/// A chat as the switcher shows it, with the project it belongs to.
#[derive(Debug, Clone, PartialEq)]
pub struct Listed {
    pub chat: ConversationInfo,
    pub project: Option<String>,
}

/// Every chat, newest first, with its project's name; chats of removed projects are left out, and the chat
/// in use shows in its project before its first message makes it a stored conversation.
pub fn listed(chats: &[ConversationInfo], ws: Option<&Workspaces>, current: &str, now: i64) -> Vec<Listed> {
    let Some(ws) = ws else {
        return chats.iter().map(|c| Listed { chat: c.clone(), project: None }).collect();
    };
    let mut out: Vec<Listed> = chats
        .iter()
        .filter(|c| !ws.hidden.contains(&c.id) || ws.project_of(&c.id).is_some())
        .map(|c| Listed { chat: c.clone(), project: ws.project_of(&c.id).map(|p| p.name.clone()) })
        .collect();
    if let Some(p) = ws.project_of(current)
        && !chats.iter().any(|c| c.id == current)
    {
        out.insert(
            0,
            Listed {
                chat: ConversationInfo { id: current.into(), title: String::new(), updated_at: now, running: false },
                project: Some(p.name.clone()),
            },
        );
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(id: &str, at: i64) -> ConversationInfo {
        ConversationInfo { id: id.into(), title: id.to_uppercase(), updated_at: at, running: false }
    }

    #[test]
    fn without_keeps_order() {
        let list = [c("a", 3), c("b", 2), c("c", 1)];
        assert_eq!(without(&list, "b").iter().map(|c| c.id.as_str()).collect::<Vec<_>>(), vec!["a", "c"]);
        assert_eq!(without(&list, "zz"), list.to_vec());
    }

    #[test]
    fn lists_with_projects() {
        let chats = [c("a", 5), c("b", 4), c("c", 3), c("d", 2)];
        let ws = Workspaces {
            projects: vec![Project { path: "/w/app/".into(), name: "app".into(), conversations: vec!["b".into(), "new".into()] }],
            hidden: vec!["d".into()],
        };
        let ids = |l: Vec<Listed>| l.iter().map(|x| (x.chat.id.clone(), x.project.clone())).collect::<Vec<_>>();
        assert_eq!(listed(&chats, None, "a", 0).len(), 4);
        assert_eq!(ids(listed(&chats, Some(&ws), "a", 0)), vec![("a".into(), None), ("b".into(), Some("app".into())), ("c".into(), None)]);
        let l = listed(&chats, Some(&ws), "new", 9);
        assert_eq!((l[0].chat.id.as_str(), l[0].chat.updated_at, l[0].project.as_deref()), ("new", 9, Some("app")));
        assert_eq!(ws.project_at("/w/app").map(|p| p.name.as_str()), Some("app"));
        assert!(ws.project_at("/w").is_none());
    }
}
