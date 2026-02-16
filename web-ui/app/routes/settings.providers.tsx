import * as React from "react";

import { Link } from "react-router";
import { Eye, EyeOff, Home, Plus, Save, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { v4 as uuidv4 } from "uuid";

import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import { Input } from "~/components/ui/input";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Switch } from "~/components/ui/switch";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";

export function meta() {
  return [{ title: "Settings - Providers" }];
}

type AnyRecord = Record<string, any>;

function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function getString(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function ensureArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : [];
}

function buildDefaultModel(): AnyRecord {
  return {
    id: uuidv4(),
    modelId: "gpt-4.1",
    displayName: "GPT-4.1",
    type: "CHAT",
    inputModalities: ["TEXT"],
    outputModalities: ["TEXT"],
    abilities: ["TOOL"],
    tools: [],
  };
}

function buildDefaultProvider(): AnyRecord {
  return {
    id: uuidv4(),
    type: "openai",
    enabled: true,
    name: "OpenAI",
    apiKey: "",
    baseUrl: "https://api.openai.com/v1",
    chatCompletionsPath: "/chat/completions",
    models: [buildDefaultModel()],
  };
}

function hasTool(model: AnyRecord, type: string): boolean {
  const tools = ensureArray<AnyRecord>(model.tools);
  return tools.some((t) => getString(t?.type).toLowerCase() === type.toLowerCase());
}

export default function SettingsProvidersPage() {
  const settings = useSettingsStore((state) => state.settings) as AnyRecord | null;
  const [draft, setDraft] = React.useState<AnyRecord | null>(settings ? deepClone(settings) : null);
  const [busy, setBusy] = React.useState(false);
  const [showKeys, setShowKeys] = React.useState(false);
  const [dirty, setDirty] = React.useState(false);

  React.useEffect(() => {
    if (!settings) return;
    setDraft(deepClone(settings));
    setDirty(false);
  }, [settings?.assistantId]);

  React.useEffect(() => {
    if (!settings) return;
    // When settings updates (SSE), keep draft in sync unless user is actively editing.
    if (dirty) return;
    setDraft(deepClone(settings));
  }, [settings, dirty]);

  const providers = ensureArray<AnyRecord>(draft?.providers);

  const updateProvider = React.useCallback(
    (index: number, patch: Partial<AnyRecord>) => {
      setDirty(true);
      setDraft((prev) => {
        if (!prev) return prev;
        const next = deepClone(prev);
        const list = ensureArray<AnyRecord>(next.providers);
        if (!list[index]) return prev;
        list[index] = { ...list[index], ...patch };
        next.providers = list;
        return next;
      });
    },
    [],
  );

  const updateModel = React.useCallback(
    (providerIndex: number, modelIndex: number, patch: Partial<AnyRecord>) => {
      setDirty(true);
      setDraft((prev) => {
        if (!prev) return prev;
        const next = deepClone(prev);
        const list = ensureArray<AnyRecord>(next.providers);
        const provider = list[providerIndex];
        if (!provider) return prev;
        const models = ensureArray<AnyRecord>(provider.models);
        if (!models[modelIndex]) return prev;
        models[modelIndex] = { ...models[modelIndex], ...patch };
        provider.models = models;
        list[providerIndex] = provider;
        next.providers = list;
        return next;
      });
    },
    [],
  );

  const addProvider = React.useCallback(() => {
    setDirty(true);
    setDraft((prev) => {
      const next = prev ? deepClone(prev) : ({} as AnyRecord);
      const list = ensureArray<AnyRecord>(next.providers);
      list.push(buildDefaultProvider());
      next.providers = list;
      return next;
    });
  }, []);

  const deleteProvider = React.useCallback((index: number) => {
    setDirty(true);
    setDraft((prev) => {
      if (!prev) return prev;
      const next = deepClone(prev);
      const list = ensureArray<AnyRecord>(next.providers);
      list.splice(index, 1);
      next.providers = list;
      return next;
    });
  }, []);

  const addModel = React.useCallback((providerIndex: number) => {
    setDirty(true);
    setDraft((prev) => {
      if (!prev) return prev;
      const next = deepClone(prev);
      const list = ensureArray<AnyRecord>(next.providers);
      const provider = list[providerIndex];
      if (!provider) return prev;
      const models = ensureArray<AnyRecord>(provider.models);
      models.push(buildDefaultModel());
      provider.models = models;
      list[providerIndex] = provider;
      next.providers = list;
      return next;
    });
  }, []);

  const deleteModel = React.useCallback((providerIndex: number, modelIndex: number) => {
    setDirty(true);
    setDraft((prev) => {
      if (!prev) return prev;
      const next = deepClone(prev);
      const list = ensureArray<AnyRecord>(next.providers);
      const provider = list[providerIndex];
      if (!provider) return prev;
      const models = ensureArray<AnyRecord>(provider.models);
      models.splice(modelIndex, 1);
      provider.models = models;
      list[providerIndex] = provider;
      next.providers = list;
      return next;
    });
  }, []);

  const save = React.useCallback(async () => {
    if (!draft) return;
    setBusy(true);
    try {
      await api.post<{ status: string }>("settings/replace", draft);
      toast.success("Settings saved");
      setDirty(false);
    } catch (error) {
      console.error("settings/replace failed", error);
      toast.error(error instanceof Error ? error.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }, [draft]);

  const toggleBuiltInTool = React.useCallback(
    async (modelId: string, tool: "search" | "url_context", enabled: boolean) => {
      setBusy(true);
      try {
        await api.post<{ status: string }>("settings/model/built-in-tool", {
          modelId,
          tool,
          enabled,
        });
      } catch (error) {
        console.error("settings/model/built-in-tool failed", error);
        toast.error(error instanceof Error ? error.message : "Update failed");
      } finally {
        setBusy(false);
      }
    },
    [],
  );

  return (
    <div className="flex h-svh flex-col bg-background">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Button asChild variant="outline" size="icon-sm" title="Back" aria-label="Back">
          <Link to="/settings">
            <Home className="size-4" />
          </Link>
        </Button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">Providers & Models</div>
          <div className="truncate text-xs text-muted-foreground">Configure provider endpoints/keys and model tool toggles</div>
        </div>

        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setShowKeys((v) => !v)}
          disabled={!draft}
          title={showKeys ? "Hide API keys" : "Show API keys"}
        >
          {showKeys ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
          {showKeys ? "Hide" : "Show"}
        </Button>

        <Button type="button" variant="default" size="sm" onClick={() => void save()} disabled={busy || !draft}>
          <Save className="size-4" />
          Save
        </Button>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-4xl space-y-4 px-4 py-6">
            <div className="flex items-center justify-between gap-2">
              <div className="text-sm font-semibold">Providers</div>
              <Button type="button" variant="secondary" size="sm" onClick={addProvider} disabled={busy}>
                <Plus className="size-4" />
                Add Provider
              </Button>
            </div>

            {providers.length === 0 ? (
              <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
                No providers configured.
              </div>
            ) : null}

            {providers.map((provider, providerIndex) => {
              const models = ensureArray<AnyRecord>(provider.models);
              const providerId = getString(provider.id);
              return (
                <div key={providerId || providerIndex} className="rounded-lg border p-4">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="min-w-0">
                      <div className="text-sm font-semibold">{getString(provider.name, "(unnamed)")}</div>
                      <div className="mt-1 text-xs text-muted-foreground">id: {providerId || "(missing)"}</div>
                    </div>
                    <div className="flex items-center gap-2">
                      <div className="text-xs text-muted-foreground">Enabled</div>
                      <Switch
                        checked={provider.enabled !== false}
                        onCheckedChange={(checked) => updateProvider(providerIndex, { enabled: checked })}
                        disabled={busy}
                      />
                      <Button
                        type="button"
                        variant="destructive"
                        size="icon-sm"
                        onClick={() => {
                          if (!window.confirm("Delete this provider?")) return;
                          deleteProvider(providerIndex);
                        }}
                        disabled={busy}
                        title="Delete provider"
                        aria-label="Delete provider"
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    </div>
                  </div>

                  <div className="mt-4 grid gap-3 sm:grid-cols-2">
                    <div>
                      <div className="mb-1 text-xs font-medium">Name</div>
                      <Input
                        value={getString(provider.name)}
                        onChange={(e) => updateProvider(providerIndex, { name: e.target.value })}
                        disabled={busy}
                      />
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium">Type</div>
                      <Input
                        value={getString(provider.type)}
                        onChange={(e) => updateProvider(providerIndex, { type: e.target.value })}
                        disabled={busy}
                        placeholder="openai / claude / google"
                      />
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium">Base URL</div>
                      <Input
                        value={getString(provider.baseUrl)}
                        onChange={(e) => updateProvider(providerIndex, { baseUrl: e.target.value })}
                        disabled={busy}
                        placeholder="https://.../v1"
                      />
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium">API Key</div>
                      <Input
                        type={showKeys ? "text" : "password"}
                        value={getString(provider.apiKey)}
                        onChange={(e) => updateProvider(providerIndex, { apiKey: e.target.value })}
                        disabled={busy}
                        placeholder="sk-..."
                        autoComplete="off"
                      />
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium">Chat Completions Path</div>
                      <Input
                        value={getString(provider.chatCompletionsPath, "/chat/completions")}
                        onChange={(e) => updateProvider(providerIndex, { chatCompletionsPath: e.target.value })}
                        disabled={busy}
                      />
                    </div>
                  </div>

                  <div className="mt-5 flex items-center justify-between gap-2">
                    <div className="text-sm font-semibold">Models</div>
                    <Button type="button" variant="secondary" size="sm" onClick={() => addModel(providerIndex)} disabled={busy}>
                      <Plus className="size-4" />
                      Add Model
                    </Button>
                  </div>

                  <div className="mt-3 space-y-3">
                    {models.length === 0 ? (
                      <div className="rounded-md border border-dashed p-4 text-center text-sm text-muted-foreground">
                        No models.
                      </div>
                    ) : null}

                    {models.map((model, modelIndex) => {
                      const modelId = getString(model.id);
                      const displayName = getString(model.displayName, modelId);
                      const searchEnabled = hasTool(model, "search");
                      const urlContextEnabled = hasTool(model, "url_context");

                      return (
                        <div key={modelId || modelIndex} className="rounded-md border p-3">
                          <div className="flex flex-wrap items-start justify-between gap-2">
                            <div className="min-w-0">
                              <div className="text-sm font-semibold">{displayName || "(unnamed model)"}</div>
                              <div className="mt-1 text-xs text-muted-foreground">id: {modelId || "(missing)"}</div>
                            </div>
                            <Button
                              type="button"
                              variant="destructive"
                              size="icon-sm"
                              onClick={() => {
                                if (!window.confirm("Delete this model?")) return;
                                deleteModel(providerIndex, modelIndex);
                              }}
                              disabled={busy}
                              title="Delete model"
                              aria-label="Delete model"
                            >
                              <Trash2 className="size-4" />
                            </Button>
                          </div>

                          <div className="mt-3 grid gap-3 sm:grid-cols-2">
                            <div>
                              <div className="mb-1 text-xs font-medium">Display Name</div>
                              <Input
                                value={getString(model.displayName)}
                                onChange={(e) => updateModel(providerIndex, modelIndex, { displayName: e.target.value })}
                                disabled={busy}
                              />
                            </div>
                            <div>
                              <div className="mb-1 text-xs font-medium">Model ID</div>
                              <Input
                                value={getString(model.modelId)}
                                onChange={(e) => updateModel(providerIndex, modelIndex, { modelId: e.target.value })}
                                disabled={busy}
                                placeholder="gpt-4.1 / claude-... / gemini-..."
                              />
                            </div>
                          </div>

                          <div className="mt-4">
                            <div className="text-xs font-medium">Built-in Tools (OpenAI tool-calling)</div>
                            <div className="mt-2 flex flex-wrap items-center gap-4">
                              <label className="flex cursor-pointer items-center gap-2 text-sm">
                                <Checkbox
                                  checked={searchEnabled}
                                  disabled={busy || !modelId}
                                  onCheckedChange={(checked) => {
                                    if (!modelId) return;
                                    void toggleBuiltInTool(modelId, "search", Boolean(checked));
                                  }}
                                />
                                <span>search</span>
                              </label>
                              <label className="flex cursor-pointer items-center gap-2 text-sm">
                                <Checkbox
                                  checked={urlContextEnabled}
                                  disabled={busy || !modelId}
                                  onCheckedChange={(checked) => {
                                    if (!modelId) return;
                                    void toggleBuiltInTool(modelId, "url_context", Boolean(checked));
                                  }}
                                />
                                <span>url_context</span>
                              </label>
                            </div>
                            <div className="mt-2 text-xs text-muted-foreground">
                              Note: These toggles call `/api/settings/model/built-in-tool` (no need to Save).
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              );
            })}

            <div className="rounded-lg border p-4 text-xs text-muted-foreground">
              Some provider-specific fields are not exposed here yet. Use Settings - Advanced for full control.
            </div>

            {dirty ? <div className="text-xs text-muted-foreground">Unsaved changes.</div> : null}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}