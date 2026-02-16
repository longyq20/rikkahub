import * as React from "react";

import { Link, useSearchParams } from "react-router";
import { BookOpen, Brain, ChevronLeft, Copy, Home, Plus, Save, Terminal, Trash2, UserCog, Wrench } from "lucide-react";
import { toast } from "sonner";
import { v4 as uuidv4 } from "uuid";

import { Button } from "~/components/ui/button";
import { useConfirm } from "~/components/confirm-dialog-provider";
import { Checkbox } from "~/components/ui/checkbox";
import { Input } from "~/components/ui/input";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";

export function meta() {
  return [{ title: "Settings - Assistants" }];
}

type AnyRecord = Record<string, unknown>;

const LOCAL_TOOL_OPTIONS: Array<{ id: string; name: string; desc: string }> = [
  {
    id: "javascript_engine",
    name: "JavaScript Engine",
    desc: "Android supports full JS runtime; portable backend keeps compatibility fields.",
  },
  {
    id: "time_info",
    name: "Time Info",
    desc: "Expose current date/time tool.",
  },
  {
    id: "clipboard",
    name: "Clipboard",
    desc: "Server runtime keeps API shape but operation is limited.",
  },
];

function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function getString(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function getNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function getBoolean(value: unknown, fallback = false): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function ensureArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : [];
}

function getStringArray(value: unknown): string[] {
  return ensureArray<unknown>(value)
    .map((item) => getString(item).trim())
    .filter((item) => item.length > 0);
}

function parseNullableFloat(raw: string): number | null {
  const text = raw.trim();
  if (!text) return null;
  const parsed = Number.parseFloat(text);
  return Number.isFinite(parsed) ? parsed : null;
}

function parseNullableInt(raw: string): number | null {
  const text = raw.trim();
  if (!text) return null;
  const parsed = Number.parseInt(text, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

function safeJsonStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return "[]";
  }
}

export default function SettingsAssistantsPage() {
  const settings = useSettingsStore((state) => state.settings) as AnyRecord | null;
  const [searchParams] = useSearchParams();
  const requestedAssistantId = searchParams.get("assistantId")?.trim() ?? "";
  const [draft, setDraft] = React.useState<AnyRecord | null>(settings ? deepClone(settings) : null);
  const [dirty, setDirty] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [selectedAssistantId, setSelectedAssistantId] = React.useState("");
  const [jsonText, setJsonText] = React.useState("");
  const [jsonError, setJsonError] = React.useState<string | null>(null);
  const [newTagName, setNewTagName] = React.useState("");
  const [customHeadersText, setCustomHeadersText] = React.useState("[]");
  const [customBodiesText, setCustomBodiesText] = React.useState("[]");
  const [customHeadersError, setCustomHeadersError] = React.useState<string | null>(null);
  const [customBodiesError, setCustomBodiesError] = React.useState<string | null>(null);
  const confirm = useConfirm();

  React.useEffect(() => {
    if (!settings || dirty) return;
    const next = deepClone(settings);
    setDraft(next);
    const assistants = ensureArray<AnyRecord>(next.assistants);
    const currentId = getString(next.assistantId);
    const selected =
      assistants.find((assistant) => getString(assistant.id) === requestedAssistantId) ??
      assistants.find((assistant) => getString(assistant.id) === currentId) ??
      assistants[0] ??
      null;
    const selectedId = getString(selected?.id);
    setSelectedAssistantId(selectedId);
    setJsonText(selected ? JSON.stringify(selected, null, 2) : "");
    setCustomHeadersText(safeJsonStringify(selected?.customHeaders ?? []));
    setCustomBodiesText(safeJsonStringify(selected?.customBodies ?? []));
    setJsonError(null);
    setCustomHeadersError(null);
    setCustomBodiesError(null);
  }, [settings, dirty, requestedAssistantId]);

  const assistants = ensureArray<AnyRecord>(draft?.assistants);
  const selectedAssistant = assistants.find((assistant) => getString(assistant.id) === selectedAssistantId) ?? null;
  const assistantTags = ensureArray<AnyRecord>(draft?.assistantTags);
  const modeInjections = ensureArray<AnyRecord>(draft?.modeInjections);
  const lorebooks = ensureArray<AnyRecord>(draft?.lorebooks);
  const mcpServers = ensureArray<AnyRecord>(draft?.mcpServers);

  React.useEffect(() => {
    setJsonText(selectedAssistant ? JSON.stringify(selectedAssistant, null, 2) : "");
    setCustomHeadersText(safeJsonStringify(selectedAssistant?.customHeaders ?? []));
    setCustomBodiesText(safeJsonStringify(selectedAssistant?.customBodies ?? []));
    setJsonError(null);
    setCustomHeadersError(null);
    setCustomBodiesError(null);
  }, [selectedAssistantId]);

  const updateDraft = React.useCallback((mutator: (next: AnyRecord) => void) => {
    setDirty(true);
    setDraft((prev) => {
      const next = prev ? deepClone(prev) : {};
      mutator(next);
      return next;
    });
  }, []);

  const updateSelectedAssistant = React.useCallback((mutator: (assistant: AnyRecord) => AnyRecord) => {
    if (!selectedAssistantId) return;
    updateDraft((next) => {
      const list = ensureArray<AnyRecord>(next.assistants);
      next.assistants = list.map((assistant) =>
        getString(assistant.id) === selectedAssistantId ? mutator(assistant) : assistant,
      );
    });
  }, [selectedAssistantId, updateDraft]);

  const updateSelectedStringList = React.useCallback((field: string, id: string, checked: boolean) => {
    updateSelectedAssistant((assistant) => {
      const current = new Set(getStringArray(assistant[field]));
      if (checked) {
        current.add(id);
      } else {
        current.delete(id);
      }
      return { ...assistant, [field]: Array.from(current) };
    });
  }, [updateSelectedAssistant]);

  const modelOptions = React.useMemo(() => {
    const items: Array<{ id: string; label: string }> = [];
    ensureArray<AnyRecord>(draft?.providers).forEach((provider) => {
      if (provider.enabled === false) return;
      const providerName = getString(provider.name) || "Provider";
      ensureArray<AnyRecord>(provider.models).forEach((model) => {
        const id = getString(model.id);
        const type = getString(model.type).toUpperCase();
        if (!id || (type && type !== "CHAT")) return;
        const modelName = getString(model.displayName) || getString(model.modelId) || id;
        items.push({ id, label: `${modelName} (${providerName})` });
      });
    });
    return items;
  }, [draft]);

  const addAssistant = React.useCallback(() => {
    updateDraft((next) => {
      const list = ensureArray<AnyRecord>(next.assistants);
      const fallbackModelId = modelOptions[0]?.id || getString(next.chatModelId) || "auto";
      const item: AnyRecord = {
        id: uuidv4(),
        name: `Assistant ${list.length + 1}`,
        chatModelId: fallbackModelId,
        tags: [],
        systemPrompt: "",
        messageTemplate: "{{ message }}",
        quickMessages: [],
        customHeaders: [],
        customBodies: [],
        mcpServers: [],
        modeInjectionIds: [],
        lorebookIds: [],
        localTools: ["time_info"],
        streamOutput: true,
      };
      list.push(item);
      next.assistants = list;
      next.assistantId = item.id;
      setSelectedAssistantId(getString(item.id));
    });
  }, [modelOptions, updateDraft]);

  const cloneAssistant = React.useCallback(() => {
    if (!selectedAssistant) return;
    updateDraft((next) => {
      const list = ensureArray<AnyRecord>(next.assistants);
      const item = deepClone(selectedAssistant);
      item.id = uuidv4();
      item.name = `${getString(selectedAssistant.name) || "Assistant"} (Clone)`;
      list.push(item);
      next.assistants = list;
      next.assistantId = item.id;
      setSelectedAssistantId(getString(item.id));
    });
  }, [selectedAssistant, updateDraft]);

  const deleteAssistant = React.useCallback(async () => {
    if (!selectedAssistant) return;
    if (assistants.length <= 1) {
      toast.error("At least one assistant is required");
      return;
    }

    const confirmed = await confirm({
      title: "Delete assistant?",
      description: `Delete assistant "${getString(selectedAssistant.name) || "Assistant"}"?`,
      confirmText: "Delete",
      cancelText: "Cancel",
      destructive: true,
    });
    if (!confirmed) return;

    updateDraft((next) => {
      const list = ensureArray<AnyRecord>(next.assistants).filter(
        (assistant) => getString(assistant.id) !== getString(selectedAssistant.id),
      );
      next.assistants = list;
      const fallbackId = getString(list[0]?.id);
      next.assistantId = fallbackId;
      setSelectedAssistantId(fallbackId);
    });
  }, [assistants.length, confirm, selectedAssistant, updateDraft]);

  const save = React.useCallback(async () => {
    if (!draft) return;
    if (customHeadersError || customBodiesError) {
      toast.error("Fix invalid custom request JSON before save");
      return;
    }
    setBusy(true);
    try {
      await api.post<{ status: string }>("settings/replace", draft);
      setDirty(false);
      toast.success("Assistant settings saved");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }, [customBodiesError, customHeadersError, draft]);

  const applyJson = React.useCallback(() => {
    if (!selectedAssistantId) return;
    try {
      const parsed = JSON.parse(jsonText) as AnyRecord;
      if (!parsed || typeof parsed !== "object") throw new Error("JSON must be object");
      parsed.id = selectedAssistantId;
      updateDraft((next) => {
        const list = ensureArray<AnyRecord>(next.assistants);
        next.assistants = list.map((assistant) =>
          getString(assistant.id) === selectedAssistantId ? parsed : assistant,
        );
      });
      setJsonError(null);
      toast.success("Assistant JSON applied");
    } catch (error) {
      setJsonError(error instanceof Error ? error.message : "Invalid JSON");
    }
  }, [jsonText, selectedAssistantId, updateDraft]);

  const applyCustomHeaders = React.useCallback(() => {
    try {
      const parsed = JSON.parse(customHeadersText) as unknown;
      if (!Array.isArray(parsed)) {
        throw new Error("customHeaders must be JSON array");
      }
      updateSelectedAssistant((assistant) => ({ ...assistant, customHeaders: parsed }));
      setCustomHeadersError(null);
      toast.success("Custom headers applied");
    } catch (error) {
      setCustomHeadersError(error instanceof Error ? error.message : "Invalid JSON");
    }
  }, [customHeadersText, updateSelectedAssistant]);

  const applyCustomBodies = React.useCallback(() => {
    try {
      const parsed = JSON.parse(customBodiesText) as unknown;
      if (!Array.isArray(parsed)) {
        throw new Error("customBodies must be JSON array");
      }
      updateSelectedAssistant((assistant) => ({ ...assistant, customBodies: parsed }));
      setCustomBodiesError(null);
      toast.success("Custom bodies applied");
    } catch (error) {
      setCustomBodiesError(error instanceof Error ? error.message : "Invalid JSON");
    }
  }, [customBodiesText, updateSelectedAssistant]);

  const addTag = React.useCallback(() => {
    const name = newTagName.trim();
    if (!name) {
      toast.error("Tag name is required");
      return;
    }
    updateDraft((next) => {
      const tags = ensureArray<AnyRecord>(next.assistantTags);
      if (tags.some((tag) => getString(tag.name).trim().toLowerCase() === name.toLowerCase())) {
        throw new Error("Tag already exists");
      }
      tags.push({ id: uuidv4(), name });
      next.assistantTags = tags;
    });
    setNewTagName("");
  }, [newTagName, updateDraft]);

  const onAddTag = React.useCallback(() => {
    try {
      addTag();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Failed to add tag");
    }
  }, [addTag]);

  const updateTagName = React.useCallback((tagId: string, name: string) => {
    updateDraft((next) => {
      const tags = ensureArray<AnyRecord>(next.assistantTags);
      next.assistantTags = tags.map((tag) => (getString(tag.id) === tagId ? { ...tag, name } : tag));
    });
  }, [updateDraft]);

  const deleteTag = React.useCallback((tagId: string) => {
    updateDraft((next) => {
      next.assistantTags = ensureArray<AnyRecord>(next.assistantTags).filter((tag) => getString(tag.id) !== tagId);
      next.assistants = ensureArray<AnyRecord>(next.assistants).map((assistant) => ({
        ...assistant,
        tags: getStringArray(assistant.tags).filter((id) => id !== tagId),
      }));
    });
  }, [updateDraft]);

  const quickMessages = ensureArray<AnyRecord>(selectedAssistant?.quickMessages);

  const mcpOptions = React.useMemo(() => {
    return mcpServers
      .filter((server) => {
        const common = (server.commonOptions as AnyRecord | undefined) ?? {};
        return getBoolean(common.enable, true);
      })
      .map((server) => {
        const common = (server.commonOptions as AnyRecord | undefined) ?? {};
        return { id: getString(server.id), name: getString(common.name) || getString(server.id) };
      })
      .filter((server) => server.id.length > 0);
  }, [mcpServers]);

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
          <div className="truncate text-sm font-medium">Assistants</div>
          <div className="truncate text-xs text-muted-foreground">
            Android assistant parity: basic + prompt + request + bindings
          </div>
        </div>
        <Button type="button" size="sm" onClick={() => void save()} disabled={busy || !draft}>
          <Save className="size-4" />Save
        </Button>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-6xl space-y-4 px-4 py-6">
            <div className="rounded-lg border p-4">
              <div className="grid gap-3 md:grid-cols-[1fr_auto] md:items-end">
                <div>
                  <div className="mb-1 text-xs font-medium">Current Assistant</div>
                  <Select
                    value={selectedAssistantId}
                    onValueChange={(id) => {
                      setSelectedAssistantId(id);
                      updateDraft((next) => {
                        next.assistantId = id;
                      });
                    }}
                    disabled={assistants.length === 0 || busy}
                  >
                    <SelectTrigger><SelectValue placeholder="Select assistant" /></SelectTrigger>
                    <SelectContent>
                      {assistants.map((assistant) => {
                        const id = getString(assistant.id);
                        return id ? <SelectItem key={id} value={id}>{getString(assistant.name) || "Assistant"}</SelectItem> : null;
                      })}
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button type="button" variant="outline" size="sm" onClick={addAssistant} disabled={busy}><Plus className="size-4" />Add</Button>
                  <Button type="button" variant="outline" size="sm" onClick={cloneAssistant} disabled={busy || !selectedAssistant}><Copy className="size-4" />Clone</Button>
                  <Button type="button" variant="destructive" size="sm" onClick={deleteAssistant} disabled={busy || !selectedAssistant || assistants.length <= 1}><Trash2 className="size-4" />Delete</Button>
                </div>
              </div>
              {dirty ? <div className="mt-2 text-xs text-muted-foreground">Unsaved changes.</div> : null}
            </div>

            {selectedAssistant ? (
              <div className="grid gap-4 xl:grid-cols-2">
                <div className="space-y-4">
                  <div className="space-y-4 rounded-lg border p-4">
                    <div className="flex items-center gap-2 text-sm font-semibold"><UserCog className="size-4" />Basic</div>
                    <div><div className="mb-1 text-xs font-medium">Name</div><Input value={getString(selectedAssistant.name)} onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, name: e.target.value }))} /></div>
                    <div>
                      <div className="mb-1 text-xs font-medium">Chat Model</div>
                      <Select value={getString(selectedAssistant.chatModelId)} onValueChange={(value) => updateSelectedAssistant((assistant) => ({ ...assistant, chatModelId: value }))}>
                        <SelectTrigger><SelectValue placeholder="Select model" /></SelectTrigger>
                        <SelectContent>{modelOptions.map((model) => <SelectItem key={model.id} value={model.id}>{model.label}</SelectItem>)}</SelectContent>
                      </Select>
                    </div>
                    <div className="grid gap-2 md:grid-cols-2">
                      <div><div className="mb-1 text-xs font-medium">Temperature</div><Input value={getNumber(selectedAssistant.temperature)?.toString() ?? ""} placeholder="null" onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, temperature: parseNullableFloat(e.target.value) }))} /></div>
                      <div><div className="mb-1 text-xs font-medium">Top P</div><Input value={getNumber(selectedAssistant.topP)?.toString() ?? ""} placeholder="null" onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, topP: parseNullableFloat(e.target.value) }))} /></div>
                      <div><div className="mb-1 text-xs font-medium">Context Message Size</div><Input value={(getNumber(selectedAssistant.contextMessageSize) ?? 0).toString()} onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, contextMessageSize: parseNullableInt(e.target.value) ?? 0 }))} /></div>
                      <div><div className="mb-1 text-xs font-medium">Thinking Budget</div><Input value={getNumber(selectedAssistant.thinkingBudget)?.toString() ?? ""} placeholder="null" onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, thinkingBudget: parseNullableInt(e.target.value) }))} /></div>
                      <div><div className="mb-1 text-xs font-medium">Max Tokens</div><Input value={getNumber(selectedAssistant.maxTokens)?.toString() ?? ""} placeholder="null" onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, maxTokens: parseNullableInt(e.target.value) }))} /></div>
                    </div>
                    <div className="grid gap-2">
                      <label className="flex items-center justify-between rounded-md border p-2 text-sm"><span>Stream Output</span><Switch checked={getBoolean(selectedAssistant.streamOutput, true)} onCheckedChange={(checked) => updateSelectedAssistant((assistant) => ({ ...assistant, streamOutput: checked }))} /></label>
                      <label className="flex items-center justify-between rounded-md border p-2 text-sm"><span>Use Assistant Avatar</span><Switch checked={getBoolean(selectedAssistant.useAssistantAvatar)} onCheckedChange={(checked) => updateSelectedAssistant((assistant) => ({ ...assistant, useAssistantAvatar: checked }))} /></label>
                      <label className="flex items-center justify-between rounded-md border p-2 text-sm"><span>Enable Memory</span><Switch checked={getBoolean(selectedAssistant.enableMemory)} onCheckedChange={(checked) => updateSelectedAssistant((assistant) => ({ ...assistant, enableMemory: checked }))} /></label>
                      <label className="flex items-center justify-between rounded-md border p-2 text-sm"><span>Use Global Memory</span><Switch checked={getBoolean(selectedAssistant.useGlobalMemory)} onCheckedChange={(checked) => updateSelectedAssistant((assistant) => ({ ...assistant, useGlobalMemory: checked }))} /></label>
                      <label className="flex items-center justify-between rounded-md border p-2 text-sm"><span>Recent Chats Reference</span><Switch checked={getBoolean(selectedAssistant.enableRecentChatsReference)} onCheckedChange={(checked) => updateSelectedAssistant((assistant) => ({ ...assistant, enableRecentChatsReference: checked }))} /></label>
                    </div>
                  </div>

                  <div className="space-y-4 rounded-lg border p-4">
                    <div className="flex items-center gap-2 text-sm font-semibold"><BookOpen className="size-4" />Prompt</div>
                    <div><div className="mb-1 text-xs font-medium">System Prompt</div><Textarea className="min-h-28" value={getString(selectedAssistant.systemPrompt)} onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, systemPrompt: e.target.value }))} /></div>
                    <div>
                      <div className="mb-1 text-xs font-medium">Message Template</div>
                      <Textarea className="min-h-24 font-mono text-xs" value={getString(selectedAssistant.messageTemplate, "{{ message }}")} onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, messageTemplate: e.target.value }))} />
                      <div className="mt-1 text-xs text-muted-foreground">Variables: <code>{"{{ role }}"}</code> <code>{"{{ message }}"}</code> <code>{"{{ time }}"}</code> <code>{"{{ date }}"}</code></div>
                    </div>
                    <div className="space-y-2">
                      <div className="flex items-center justify-between"><div className="text-xs font-medium">Quick Messages</div><Button type="button" variant="outline" size="sm" onClick={() => updateSelectedAssistant((assistant) => ({ ...assistant, quickMessages: [...ensureArray<AnyRecord>(assistant.quickMessages), { title: "", content: "" }] }))}><Plus className="size-4" />Add</Button></div>
                      {quickMessages.length === 0 ? <div className="rounded-md border border-dashed p-3 text-xs text-muted-foreground">No quick messages.</div> : null}
                      {quickMessages.map((item, index) => (
                        <div key={`quick-${index}`} className="space-y-2 rounded-md border p-3">
                          <div className="flex items-center gap-2">
                            <Input placeholder="Title" value={getString(item.title)} onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, quickMessages: ensureArray<AnyRecord>(assistant.quickMessages).map((row, rowIndex) => rowIndex === index ? { ...row, title: e.target.value } : row) }))} />
                            <Button type="button" variant="destructive" size="icon-sm" onClick={() => updateSelectedAssistant((assistant) => ({ ...assistant, quickMessages: ensureArray<AnyRecord>(assistant.quickMessages).filter((_row, rowIndex) => rowIndex !== index) }))}><Trash2 className="size-4" /></Button>
                          </div>
                          <Textarea placeholder="Content" className="min-h-20" value={getString(item.content)} onChange={(e) => updateSelectedAssistant((assistant) => ({ ...assistant, quickMessages: ensureArray<AnyRecord>(assistant.quickMessages).map((row, rowIndex) => rowIndex === index ? { ...row, content: e.target.value } : row) }))} />
                        </div>
                      ))}
                    </div>
                  </div>
                </div>

                <div className="space-y-4">
                  <div className="space-y-3 rounded-lg border p-4">
                    <div className="flex items-center gap-2 text-sm font-semibold"><Wrench className="size-4" />Request Overrides</div>
                    <div>
                      <div className="mb-1 text-xs font-medium">customHeaders (JSON array)</div>
                      <Textarea className="min-h-32 font-mono text-xs" value={customHeadersText} onChange={(e) => setCustomHeadersText(e.target.value)} />
                      {customHeadersError ? <div className="mt-1 text-xs text-destructive">{customHeadersError}</div> : null}
                      <Button type="button" variant="outline" size="sm" className="mt-2" onClick={applyCustomHeaders}>Apply Headers</Button>
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium">customBodies (JSON array)</div>
                      <Textarea className="min-h-32 font-mono text-xs" value={customBodiesText} onChange={(e) => setCustomBodiesText(e.target.value)} />
                      {customBodiesError ? <div className="mt-1 text-xs text-destructive">{customBodiesError}</div> : null}
                      <Button type="button" variant="outline" size="sm" className="mt-2" onClick={applyCustomBodies}>Apply Bodies</Button>
                    </div>
                  </div>

                  <div className="space-y-4 rounded-lg border p-4">
                    <div className="flex items-center gap-2 text-sm font-semibold"><Brain className="size-4" />Bindings</div>

                    <div className="space-y-2 rounded-md border p-3">
                      <div className="text-xs font-medium">Tags</div>
                      <div className="flex flex-wrap gap-2">
                        {assistantTags.map((tag) => {
                          const id = getString(tag.id);
                          if (!id) return null;
                          const checked = getStringArray(selectedAssistant.tags).includes(id);
                          return (
                            <label key={id} className={`flex items-center gap-2 rounded-md border px-2 py-1 text-xs ${checked ? "border-primary bg-primary/5" : ""}`}>
                              <Checkbox checked={checked} onCheckedChange={(next) => updateSelectedStringList("tags", id, Boolean(next))} />
                              <span>{getString(tag.name) || "Tag"}</span>
                            </label>
                          );
                        })}
                      </div>
                      <div className="space-y-2 rounded-md border border-dashed p-2">
                        <div className="text-xs text-muted-foreground">Manage tag library</div>
                        <div className="flex gap-2"><Input placeholder="New tag" value={newTagName} onChange={(e) => setNewTagName(e.target.value)} /><Button type="button" size="sm" variant="outline" onClick={onAddTag}><Plus className="size-4" />Add</Button></div>
                        {assistantTags.map((tag) => {
                          const id = getString(tag.id);
                          if (!id) return null;
                          return (
                            <div key={`tag-manage-${id}`} className="flex items-center gap-2">
                              <Input value={getString(tag.name)} onChange={(e) => updateTagName(id, e.target.value)} />
                              <Button type="button" variant="destructive" size="icon-sm" onClick={() => deleteTag(id)}><Trash2 className="size-4" /></Button>
                            </div>
                          );
                        })}
                      </div>
                    </div>

                    <div className="space-y-2 rounded-md border p-3">
                      <div className="text-xs font-medium">Local Tools</div>
                      {LOCAL_TOOL_OPTIONS.map((tool) => {
                        const checked = getStringArray(selectedAssistant.localTools).includes(tool.id);
                        return (
                          <label key={tool.id} className="flex items-start gap-2 rounded-md border p-2 text-sm">
                            <Checkbox checked={checked} onCheckedChange={(next) => updateSelectedStringList("localTools", tool.id, Boolean(next))} />
                            <div><div>{tool.name}</div><div className="text-xs text-muted-foreground">{tool.desc}</div></div>
                          </label>
                        );
                      })}
                    </div>

                    <div className="space-y-2 rounded-md border p-3">
                      <div className="flex items-center gap-2 text-xs font-medium"><Terminal className="size-3.5" />MCP Servers</div>
                      {mcpOptions.length === 0 ? <div className="text-xs text-muted-foreground">No enabled MCP servers configured.</div> : mcpOptions.map((server) => {
                        const checked = getStringArray(selectedAssistant.mcpServers).includes(server.id);
                        return <label key={server.id} className="flex items-center gap-2 rounded-md border p-2 text-sm"><Checkbox checked={checked} onCheckedChange={(next) => updateSelectedStringList("mcpServers", server.id, Boolean(next))} /><span>{server.name}</span></label>;
                      })}
                    </div>

                    <div className="space-y-2 rounded-md border p-3">
                      <div className="text-xs font-medium">Prompt Injections</div>
                      <div className="space-y-1">
                        <div className="text-xs text-muted-foreground">Mode Injections</div>
                        {modeInjections.length === 0 ? <div className="text-xs text-muted-foreground">No mode injections configured.</div> : modeInjections.map((item) => {
                          const id = getString(item.id);
                          if (!id) return null;
                          const checked = getStringArray(selectedAssistant.modeInjectionIds).includes(id);
                          return <label key={id} className="flex items-center gap-2 rounded-md border p-2 text-sm"><Checkbox checked={checked} onCheckedChange={(next) => updateSelectedStringList("modeInjectionIds", id, Boolean(next))} /><span>{getString(item.name) || id}</span></label>;
                        })}
                      </div>
                      <div className="space-y-1">
                        <div className="text-xs text-muted-foreground">Lorebooks</div>
                        {lorebooks.length === 0 ? <div className="text-xs text-muted-foreground">No lorebooks configured.</div> : lorebooks.map((item) => {
                          const id = getString(item.id);
                          if (!id) return null;
                          const checked = getStringArray(selectedAssistant.lorebookIds).includes(id);
                          return <label key={id} className="flex items-center gap-2 rounded-md border p-2 text-sm"><Checkbox checked={checked} onCheckedChange={(next) => updateSelectedStringList("lorebookIds", id, Boolean(next))} /><span>{getString(item.name) || id}</span></label>;
                        })}
                      </div>
                    </div>
                  </div>

                  <div className="space-y-2 rounded-lg border p-4">
                    <div className="text-sm font-semibold">Assistant JSON (Advanced)</div>
                    <div className="text-xs text-muted-foreground">For remaining fields not surfaced yet: avatar/background/presetMessages/regexes and future compatibility fields.</div>
                    <Textarea className="min-h-[20rem] font-mono text-xs" value={jsonText} onChange={(e) => setJsonText(e.target.value)} />
                    {jsonError ? <div className="text-xs text-destructive">{jsonError}</div> : null}
                    <Button type="button" variant="outline" size="sm" onClick={applyJson}>Apply JSON To Assistant</Button>
                  </div>
                </div>
              </div>
            ) : (
              <div className="rounded-lg border border-dashed p-6 text-sm text-muted-foreground">No assistant selected.</div>
            )}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}

