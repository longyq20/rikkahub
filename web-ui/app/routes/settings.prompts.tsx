import * as React from "react";

import { Link } from "react-router";
import { ChevronLeft, Home, Plus, Save, Sparkles } from "lucide-react";
import { toast } from "sonner";
import { v4 as uuidv4 } from "uuid";

import { Button } from "~/components/ui/button";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Textarea } from "~/components/ui/textarea";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";

export function meta() {
  return [{ title: "Settings - Prompts" }];
}

type AnyRecord = Record<string, unknown>;

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return "";
  }
}

function parseJson(raw: string): unknown {
  const text = raw.trim();
  if (text.length === 0) return null;
  return JSON.parse(text) as unknown;
}

function defaultModeInjection(): AnyRecord {
  return {
    id: uuidv4(),
    type: "mode",
    name: "Mode Injection",
    enabled: true,
    priority: 0,
    position: "after_system_prompt",
    content: "",
    injectDepth: 4,
    role: "USER",
  };
}

function defaultLorebook(): AnyRecord {
  return {
    id: uuidv4(),
    name: "Lorebook",
    description: "",
    enabled: true,
    entries: [],
  };
}

export default function SettingsPromptsPage() {
  const settings = useSettingsStore((state) => state.settings) as AnyRecord | null;
  const [busy, setBusy] = React.useState(false);
  const [dirty, setDirty] = React.useState(false);
  const [modeText, setModeText] = React.useState(() => safeStringify(settings?.modeInjections ?? []));
  const [loreText, setLoreText] = React.useState(() => safeStringify(settings?.lorebooks ?? []));

  React.useEffect(() => {
    if (dirty) return;
    setModeText(safeStringify(settings?.modeInjections ?? []));
    setLoreText(safeStringify(settings?.lorebooks ?? []));
  }, [settings?.modeInjections, settings?.lorebooks, dirty]);

  const addModeInjection = React.useCallback(() => {
    let current: unknown;
    try {
      current = parseJson(modeText);
    } catch {
      toast.error("Invalid modeInjections JSON");
      return;
    }

    const list = Array.isArray(current) ? current.slice() : [];
    list.push(defaultModeInjection());
    setModeText(JSON.stringify(list, null, 2));
    setDirty(true);
  }, [modeText]);

  const addLorebook = React.useCallback(() => {
    let current: unknown;
    try {
      current = parseJson(loreText);
    } catch {
      toast.error("Invalid lorebooks JSON");
      return;
    }

    const list = Array.isArray(current) ? current.slice() : [];
    list.push(defaultLorebook());
    setLoreText(JSON.stringify(list, null, 2));
    setDirty(true);
  }, [loreText]);

  const save = React.useCallback(async () => {
    if (!settings) return;

    let modeInjections: unknown;
    let lorebooks: unknown;
    try {
      modeInjections = parseJson(modeText);
      lorebooks = parseJson(loreText);
    } catch {
      toast.error("Invalid JSON");
      return;
    }

    if (!Array.isArray(modeInjections)) {
      toast.error("modeInjections must be a JSON array");
      return;
    }
    if (!Array.isArray(lorebooks)) {
      toast.error("lorebooks must be a JSON array");
      return;
    }

    setBusy(true);
    try {
      const nextSettings = {
        ...settings,
        modeInjections,
        lorebooks,
      };
      await api.post<{ status: string }>("settings/replace", nextSettings);
      toast.success("Prompts saved");
      setDirty(false);
    } catch (error) {
      console.error("settings/replace failed", error);
      toast.error(error instanceof Error ? error.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }, [settings, modeText, loreText]);

  return (
    <div className="flex h-svh flex-col bg-background">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Button asChild variant="outline" size="icon-sm" title="Back to settings" aria-label="Back to settings">
          <Link to="/settings">
            <ChevronLeft className="size-4" />
          </Link>
        </Button>
        <Button asChild variant="outline" size="icon-sm" title="Back to chats" aria-label="Back to chats">
          <Link to="/">
            <Home className="size-4" />
          </Link>
        </Button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">Prompts</div>
          <div className="truncate text-xs text-muted-foreground">
            Manage mode injections and lorebooks in settings.json
          </div>
        </div>

        <Button type="button" variant="secondary" size="sm" onClick={addModeInjection} disabled={busy}>
          <Plus className="size-4" />
          Add Mode
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={addLorebook} disabled={busy}>
          <Plus className="size-4" />
          Add Lorebook
        </Button>
        <Button type="button" variant="default" size="sm" onClick={() => void save()} disabled={busy || !settings}>
          <Save className="size-4" />
          Save
        </Button>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-5xl space-y-4 px-4 py-6">
            <div className="rounded-lg border p-4">
              <div className="text-sm font-semibold">modeInjections</div>
              <div className="mt-1 text-xs text-muted-foreground">
                Compatible with Android PromptInjection.ModeInjection fields (id/type/name/enabled/content/...)
              </div>
              <Textarea
                value={modeText}
                onChange={(e) => {
                  setModeText(e.target.value);
                  setDirty(true);
                }}
                className="mt-3 min-h-[28vh] font-mono text-xs"
                spellCheck={false}
              />
            </div>

            <div className="rounded-lg border p-4">
              <div className="text-sm font-semibold">lorebooks</div>
              <div className="mt-1 text-xs text-muted-foreground">
                Each lorebook may include entries (RegexInjection list) for context-triggered prompt injection.
              </div>
              <Textarea
                value={loreText}
                onChange={(e) => {
                  setLoreText(e.target.value);
                  setDirty(true);
                }}
                className="mt-3 min-h-[28vh] font-mono text-xs"
                spellCheck={false}
              />
            </div>

            <div className="rounded-lg border p-4 text-xs text-muted-foreground">
              <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <Sparkles className="size-4" />
                Notes
              </div>
              <ul className="mt-2 list-disc pl-5">
                <li>After editing templates, use the chat input injection picker to bind IDs to current assistant.</li>
                <li>For bulk or risky edits, export a backup first from Settings - Backup.</li>
              </ul>
            </div>

            {dirty ? <div className="text-xs text-muted-foreground">Unsaved changes.</div> : null}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}