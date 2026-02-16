import * as React from "react";

import { Link } from "react-router";
import { ChevronLeft, ChevronRight, Download, Eye, EyeOff, Home, Plus, Save, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { v4 as uuidv4 } from "uuid";

import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import { Input } from "~/components/ui/input";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { useConfirm } from "~/components/confirm-dialog-provider";
import api from "~/services/api";
import type { FetchProviderModelsRequestDto, FetchProviderModelsResponseDto, ProviderModelFetchDto } from "~/types/dto";
import { useSettingsStore } from "~/stores";

export function meta() {
  return [{ title: "Settings - Providers" }];
}

type AnyRecord = Record<string, unknown>;
type ProviderType = "openai" | "google" | "claude";
type ModelType = "CHAT" | "IMAGE" | "EMBEDDING";
type ModelModality = "TEXT" | "IMAGE";
type ModelAbility = "TOOL" | "REASONING";
type ProxyType = "none" | "http" | "socks5";

interface ProviderFetchState {
  loading: boolean;
  models: ProviderModelFetchDto[];
  selected: Record<string, boolean>;
  error: string | null;
}

interface ProviderProxyDraft {
  type: ProxyType;
  address: string;
  port: number;
  username: string;
  password: string;
}

const PROVIDER_TYPES: Array<{ value: ProviderType; label: string }> = [
  { value: "openai", label: "OpenAI" },
  { value: "google", label: "Google" },
  { value: "claude", label: "Claude" },
];

const MODEL_TYPES: Array<{ value: ModelType; label: string }> = [
  { value: "CHAT", label: "CHAT" },
  { value: "IMAGE", label: "IMAGE" },
  { value: "EMBEDDING", label: "EMBEDDING" },
];

const PROXY_TYPES: Array<{ value: ProxyType; label: string }> = [
  { value: "none", label: "None" },
  { value: "http", label: "HTTP" },
  { value: "socks5", label: "SOCKS5" },
];

const DEFAULT_TITLE_PROMPT = [
  "I will give you some dialogue content in the <content> block.",
  "You need to summarize the conversation between user and assistant into a short title.",
  "1. The title language should be consistent with the user's primary language",
  "2. Do not use punctuation or other special symbols",
  "3. Reply directly with the title",
  "4. Summarize using {locale} language",
  "5. The title should not exceed 10 characters",
  "",
  "<content>",
  "{content}",
  "</content>",
].join("\n");

function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function getString(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function getBoolean(value: unknown, fallback = false): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function getNumber(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function ensureArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : [];
}

function normalizeProviderType(value: unknown): ProviderType {
  const lower = getString(value).trim().toLowerCase();
  if (lower === "google") return "google";
  if (lower === "claude") return "claude";
  return "openai";
}

function normalizeModelType(value: unknown): ModelType {
  const upper = getString(value).trim().toUpperCase();
  if (upper === "IMAGE") return "IMAGE";
  if (upper === "EMBEDDING") return "EMBEDDING";
  return "CHAT";
}

function providerDefaults(type: ProviderType): AnyRecord {
  if (type === "google") {
    return {
      name: "Google",
      baseUrl: "https://generativelanguage.googleapis.com/v1beta",
      vertexAI: false,
    };
  }
  if (type === "claude") {
    return {
      name: "Claude",
      baseUrl: "https://api.anthropic.com/v1",
    };
  }
  return {
    name: "OpenAI",
    baseUrl: "https://api.openai.com/v1",
    chatCompletionsPath: "/chat/completions",
    useResponseApi: false,
  };
}

function buildDefaultProxy(): ProviderProxyDraft {
  return {
    type: "none",
    address: "",
    port: 7890,
    username: "",
    password: "",
  };
}

function readProxy(provider: AnyRecord): ProviderProxyDraft {
  const source = (provider.proxy as AnyRecord | undefined) ?? {};
  const typeRaw = getString(source.type).trim().toLowerCase();
  const type: ProxyType = typeRaw === "http" || typeRaw === "socks5" ? typeRaw : "none";
  return {
    type,
    address: getString(source.address),
    port: getNumber(source.port, 7890),
    username: getString(source.username),
    password: getString(source.password),
  };
}

function buildDefaultModel(seed?: Partial<ProviderModelFetchDto>): AnyRecord {
  const type = normalizeModelType(seed?.type);
  const hasImage = type === "IMAGE";
  return {
    id: uuidv4(),
    modelId: getString(seed?.modelId, "gpt-4.1"),
    displayName: getString(seed?.displayName, getString(seed?.modelId, "GPT-4.1")),
    type,
    inputModalities: hasImage ? ["TEXT", "IMAGE"] : ["TEXT"],
    outputModalities: hasImage ? ["TEXT", "IMAGE"] : ["TEXT"],
    abilities: ["TOOL"],
    tools: [],
  };
}

function buildDefaultProvider(type: ProviderType = "openai"): AnyRecord {
  return {
    id: uuidv4(),
    enabled: true,
    apiKey: "",
    models: [buildDefaultModel()],
    proxy: buildDefaultProxy(),
    ...providerDefaults(type),
    type,
  };
}

function hasBuiltInTool(model: AnyRecord, toolType: string): boolean {
  return ensureArray<AnyRecord>(model.tools).some((tool) => getString(tool.type).toLowerCase() === toolType.toLowerCase());
}

function toggleItem(values: string[], item: string, enabled: boolean): string[] {
  const normalized = values.filter((value) => value.trim().length > 0);
  if (enabled) {
    return normalized.includes(item) ? normalized : [...normalized, item];
  }
  return normalized.filter((value) => value !== item);
}

function upsertModelTool(model: AnyRecord, toolType: "search" | "url_context", enabled: boolean): AnyRecord[] {
  const tools = ensureArray<AnyRecord>(model.tools).filter((tool) => getString(tool.type) !== toolType);
  if (enabled) tools.push({ type: toolType });
  return tools;
}

function normalizeModelRef(value: unknown): string {
  return getString(value).trim().toLowerCase();
}

function modelAliasesFromValue(value: unknown): string[] {
  const normalized = normalizeModelRef(value);
  if (normalized.length === 0) return [];

  const aliases = new Set<string>([normalized]);
  const slashIndex = normalized.lastIndexOf("/");
  if (slashIndex >= 0 && slashIndex + 1 < normalized.length) {
    aliases.add(normalized.slice(slashIndex + 1));
  }
  return [...aliases];
}

function modelAliases(model: AnyRecord): string[] {
  const aliases = new Set<string>();
  modelAliasesFromValue(model.modelId).forEach((value) => aliases.add(value));
  modelAliasesFromValue(model.id).forEach((value) => aliases.add(value));
  return [...aliases];
}

function fetchedModelAliases(model: ProviderModelFetchDto): string[] {
  return modelAliasesFromValue(model.modelId);
}

function isChatSelectableModel(model: AnyRecord): boolean {
  const type = normalizeModelType(model.type);
  if (type === "CHAT") {
    return true;
  }
  if (type !== "IMAGE") {
    return false;
  }

  const inputModalities = ensureArray<unknown>(model.inputModalities)
    .map((item) => getString(item).trim().toUpperCase())
    .filter((item) => item.length > 0);

  return inputModalities.length === 0 || inputModalities.includes("TEXT");
}
function modelKey(model: AnyRecord): string {
  return modelAliases(model)[0] ?? "";
}

export default function SettingsProvidersPage() {
  const settings = useSettingsStore((state) => state.settings) as AnyRecord | null;
  const [draft, setDraft] = React.useState<AnyRecord | null>(settings ? deepClone(settings) : null);
  const [busy, setBusy] = React.useState(false);
  const [showSecrets, setShowSecrets] = React.useState(false);
  const [dirty, setDirty] = React.useState(false);
  const [addProviderType, setAddProviderType] = React.useState<ProviderType>("openai");
  const [fetchStates, setFetchStates] = React.useState<Record<string, ProviderFetchState>>({});
  const [collapsedProviders, setCollapsedProviders] = React.useState<Record<string, boolean>>({});
  const [defaultModelsCollapsed, setDefaultModelsCollapsed] = React.useState(false);
  const [titleSummaryCollapsed, setTitleSummaryCollapsed] = React.useState(false);
  const confirm = useConfirm();

  React.useEffect(() => {
    if (!settings || dirty) return;
    setDraft(deepClone(settings));
  }, [settings, dirty]);

  const providers = ensureArray<AnyRecord>(draft?.providers);
  const titleModelOptions = React.useMemo(() => {
    const options: Array<{ id: string; label: string }> = [];
    const seen = new Set<string>();

    providers.forEach((provider, providerIndex) => {
      const providerName = getString(provider.name, "Provider " + String(providerIndex + 1));
      const models = ensureArray<AnyRecord>(provider.models);

      models.forEach((model) => {
        if (normalizeModelType(model.type) !== "CHAT") return;
        const id = getString(model.id).trim();
        if (!id || seen.has(id)) return;
        seen.add(id);

        const displayName = getString(model.displayName, id).trim() || id;
        const modelId = getString(model.modelId).trim();
        const suffix = modelId ? " (" + providerName + " / " + modelId + ")" : " (" + providerName + ")";
        options.push({ id, label: displayName + suffix });
      });
    });

    if (!seen.has("auto")) {
      options.unshift({ id: "auto", label: "Auto" });
    }

    return options;
  }, [providers]);

  const selectedTitleModelId = React.useMemo(() => {
    const explicit = getString(draft?.titleModelId).trim();
    if (explicit) return explicit;
    const fallback = getString(draft?.chatModelId).trim();
    if (fallback) return fallback;
    return "auto";
  }, [draft?.titleModelId, draft?.chatModelId]);

  const titlePromptValue = getString(draft?.titlePrompt, DEFAULT_TITLE_PROMPT);
  const titleModelSelectValue = titleModelOptions.some((option) => option.id === selectedTitleModelId)
    ? selectedTitleModelId
    : "auto";
  React.useEffect(() => {
    setCollapsedProviders((prev) => {
      const next: Record<string, boolean> = {};
      let changed = false;

      providers.forEach((provider, index) => {
        const key = getString(provider.id) || `provider-${index}`;
        if (Object.prototype.hasOwnProperty.call(prev, key)) {
          next[key] = prev[key];
        } else {
          next[key] = true;
          changed = true;
        }
      });

      if (!changed && Object.keys(prev).length !== Object.keys(next).length) {
        changed = true;
      }

      return changed ? next : prev;
    });
  }, [providers]);

  const toggleProviderCollapsed = React.useCallback((providerKey: string) => {
    setCollapsedProviders((prev) => ({
      ...prev,
      [providerKey]: !(prev[providerKey] ?? true),
    }));
  }, []);

  const updateDraft = React.useCallback((mutator: (next: AnyRecord) => void) => {
    setDirty(true);
    setDraft((prev) => {
      const next = prev ? deepClone(prev) : {};
      mutator(next);
      return next;
    });
  }, []);

  const updateTitleModel = React.useCallback(
    (modelId: string) => {
      updateDraft((next) => {
        next.titleModelId = modelId;
        if (getString(next.titlePrompt).trim().length === 0) {
          next.titlePrompt = DEFAULT_TITLE_PROMPT;
        }
      });
    },
    [updateDraft],
  );

  const updateTitlePrompt = React.useCallback(
    (prompt: string) => {
      updateDraft((next) => {
        next.titlePrompt = prompt;
      });
    },
    [updateDraft],
  );

  const resetTitlePrompt = React.useCallback(() => {
    updateDraft((next) => {
      next.titlePrompt = DEFAULT_TITLE_PROMPT;
    });
  }, [updateDraft]);

  const updateProvider = React.useCallback(
    (providerIndex: number, patch: Partial<AnyRecord>) => {
      updateDraft((next) => {
        const list = ensureArray<AnyRecord>(next.providers);
        const provider = list[providerIndex];
        if (!provider) return;
        list[providerIndex] = { ...provider, ...patch };
        next.providers = list;
      });
    },
    [updateDraft],
  );

  const updateProviderType = React.useCallback(
    (providerIndex: number, nextType: ProviderType) => {
      updateDraft((next) => {
        const list = ensureArray<AnyRecord>(next.providers);
        const current = list[providerIndex];
        if (!current) return;

        const previousType = normalizeProviderType(current.type);
        const nextDefaults = providerDefaults(nextType);
        const previousDefaults = providerDefaults(previousType);

        const currentName = getString(current.name);
        const currentBaseUrl = getString(current.baseUrl);

        const mutated: AnyRecord = {
          ...current,
          ...nextDefaults,
          type: nextType,
          name:
            currentName.length === 0 || currentName === getString(previousDefaults.name)
              ? getString(nextDefaults.name)
              : currentName,
          baseUrl:
            currentBaseUrl.length === 0 || currentBaseUrl === getString(previousDefaults.baseUrl)
              ? getString(nextDefaults.baseUrl)
              : currentBaseUrl,
        };

        if (nextType !== "openai") {
          delete mutated.chatCompletionsPath;
          delete mutated.useResponseApi;
        } else {
          mutated.chatCompletionsPath = getString(mutated.chatCompletionsPath, "/chat/completions");
          mutated.useResponseApi = getBoolean(mutated.useResponseApi, false);
        }

        if (nextType !== "google") {
          delete mutated.vertexAI;
          delete mutated.privateKey;
          delete mutated.serviceAccountEmail;
          delete mutated.location;
          delete mutated.projectId;
        } else {
          mutated.vertexAI = getBoolean(mutated.vertexAI, false);
          mutated.privateKey = getString(mutated.privateKey);
          mutated.serviceAccountEmail = getString(mutated.serviceAccountEmail);
          mutated.location = getString(mutated.location, "us-central1");
          mutated.projectId = getString(mutated.projectId);
        }

        list[providerIndex] = mutated;
        next.providers = list;
      });
    },
    [updateDraft],
  );

  const updateProviderProxy = React.useCallback(
    (providerIndex: number, patch: Partial<ProviderProxyDraft>) => {
      updateDraft((next) => {
        const list = ensureArray<AnyRecord>(next.providers);
        const provider = list[providerIndex];
        if (!provider) return;
        const proxy = readProxy(provider);
        list[providerIndex] = {
          ...provider,
          proxy: {
            ...proxy,
            ...patch,
          },
        };
        next.providers = list;
      });
    },
    [updateDraft],
  );

  const updateModel = React.useCallback(
    (providerIndex: number, modelIndex: number, patch: Partial<AnyRecord>) => {
      updateDraft((next) => {
        const providersList = ensureArray<AnyRecord>(next.providers);
        const provider = providersList[providerIndex];
        if (!provider) return;

        const models = ensureArray<AnyRecord>(provider.models);
        const model = models[modelIndex];
        if (!model) return;

        models[modelIndex] = { ...model, ...patch };
        providersList[providerIndex] = { ...provider, models };
        next.providers = providersList;
      });
    },
    [updateDraft],
  );

  const updateModelArrayField = React.useCallback(
    (
      providerIndex: number,
      modelIndex: number,
      field: "inputModalities" | "outputModalities" | "abilities",
      item: string,
      enabled: boolean,
    ) => {
      updateDraft((next) => {
        const providersList = ensureArray<AnyRecord>(next.providers);
        const provider = providersList[providerIndex];
        if (!provider) return;

        const models = ensureArray<AnyRecord>(provider.models);
        const model = models[modelIndex];
        if (!model) return;

        const source = ensureArray<string>(model[field]);
        const values = toggleItem(source, item, enabled);
        models[modelIndex] = { ...model, [field]: values };
        providersList[providerIndex] = { ...provider, models };
        next.providers = providersList;
      });
    },
    [updateDraft],
  );

  const updateModelBuiltInTool = React.useCallback(
    (providerIndex: number, modelIndex: number, toolType: "search" | "url_context", enabled: boolean) => {
      updateDraft((next) => {
        const providersList = ensureArray<AnyRecord>(next.providers);
        const provider = providersList[providerIndex];
        if (!provider) return;

        const models = ensureArray<AnyRecord>(provider.models);
        const model = models[modelIndex];
        if (!model) return;

        models[modelIndex] = {
          ...model,
          tools: upsertModelTool(model, toolType, enabled),
        };
        providersList[providerIndex] = { ...provider, models };
        next.providers = providersList;
      });
    },
    [updateDraft],
  );

  const addProvider = React.useCallback(
    (providerType: ProviderType) => {
      updateDraft((next) => {
        const list = ensureArray<AnyRecord>(next.providers);
        list.push(buildDefaultProvider(providerType));
        next.providers = list;
      });
    },
    [updateDraft],
  );

  const deleteProvider = React.useCallback(
    (providerIndex: number) => {
      updateDraft((next) => {
        const list = ensureArray<AnyRecord>(next.providers);
        list.splice(providerIndex, 1);
        next.providers = list;
      });
    },
    [updateDraft],
  );

  const addModel = React.useCallback(
    (providerIndex: number) => {
      updateDraft((next) => {
        const providersList = ensureArray<AnyRecord>(next.providers);
        const provider = providersList[providerIndex];
        if (!provider) return;

        const models = ensureArray<AnyRecord>(provider.models);
        models.push(buildDefaultModel());
        providersList[providerIndex] = { ...provider, models };
        next.providers = providersList;
      });
    },
    [updateDraft],
  );

  const deleteModel = React.useCallback(
    (providerIndex: number, modelIndex: number) => {
      updateDraft((next) => {
        const providersList = ensureArray<AnyRecord>(next.providers);
        const provider = providersList[providerIndex];
        if (!provider) return;

        const models = ensureArray<AnyRecord>(provider.models);
        models.splice(modelIndex, 1);
        providersList[providerIndex] = { ...provider, models };
        next.providers = providersList;
      });
    },
    [updateDraft],
  );

  const setFetchState = React.useCallback(
    (providerId: string, updater: (prev: ProviderFetchState) => ProviderFetchState) => {
      setFetchStates((prev) => {
        const current = prev[providerId] ?? {
          loading: false,
          models: [],
          selected: {},
          error: null,
        };
        return {
          ...prev,
          [providerId]: updater(current),
        };
      });
    },
    [],
  );

  const fetchProviderModels = React.useCallback(
    async (providerIndex: number) => {
      const provider = providers[providerIndex];
      if (!provider) return;
      const providerId = getString(provider.id);
      if (!providerId) {
        toast.error("Provider id is missing");
        return;
      }

      setFetchState(providerId, (state) => ({ ...state, loading: true, error: null }));
      try {
        if (draft) {
          await api.post<{ status: string }>("settings/replace", draft);
          setDirty(false);
        }

        const requestBody: FetchProviderModelsRequestDto = { providerId };
        const response = await api.post<FetchProviderModelsResponseDto>("settings/provider/models/fetch", requestBody);

        const existingModelIds = new Set(
          ensureArray<AnyRecord>(provider.models)
            .flatMap((model) => modelAliases(model))
            .filter((value) => value.length > 0),
        );

        const selected: Record<string, boolean> = {};
        response.models.forEach((model) => {
          selected[model.modelId] = fetchedModelAliases(model).some((value) => existingModelIds.has(value));
        });

        setFetchState(providerId, () => ({
          loading: false,
          models: response.models,
          selected,
          error: null,
        }));
        toast.success(`Fetched ${response.models.length} models`);
      } catch (error) {
        console.error("settings/provider/models/fetch failed", error);
        const message = error instanceof Error ? error.message : "Fetch models failed";
        setFetchState(providerId, (state) => ({
          ...state,
          loading: false,
          error: message,
        }));
        toast.error(message);
      }
    },
    [providers, setFetchState, draft],
  );

  const toggleFetchedSelection = React.useCallback(
    (providerId: string, modelId: string, checked: boolean) => {
      setFetchState(providerId, (state) => ({
        ...state,
        selected: {
          ...state.selected,
          [modelId]: checked,
        },
      }));
    },
    [setFetchState],
  );

  const importFetchedModels = React.useCallback(
    (providerIndex: number) => {
      const provider = providers[providerIndex];
      if (!provider) return;

      const providerId = getString(provider.id);
      const state = fetchStates[providerId];
      if (!state || state.models.length === 0) {
        toast.error("No fetched models to import");
        return;
      }

      const selectedModels = state.models.filter((model) => state.selected[model.modelId]);
      if (selectedModels.length === 0) {
        toast.error("Select at least one model");
        return;
      }

      const existingModelIds = new Set(
        ensureArray<AnyRecord>(provider.models)
          .flatMap((model) => modelAliases(model))
          .filter((value) => value.length > 0),
      );
      const importable = selectedModels.filter((model) => !fetchedModelAliases(model).some((value) => existingModelIds.has(value)));
      if (importable.length === 0) {
        toast.message("Selected models already exist");
        return;
      }

      updateDraft((next) => {
        const providersList = ensureArray<AnyRecord>(next.providers);
        const target = providersList[providerIndex];
        if (!target) return;

        const models = ensureArray<AnyRecord>(target.models);
        importable.forEach((item) => {
          models.push(
            buildDefaultModel({
              modelId: item.modelId,
              displayName: item.displayName,
              type: item.type,
            }),
          );
        });

        providersList[providerIndex] = {
          ...target,
          models,
        };
        next.providers = providersList;
      });

      setFetchState(providerId, (prev) => {
        const selected = { ...prev.selected };
        importable.forEach((model) => {
          selected[model.modelId] = false;
        });
        return { ...prev, selected };
      });

      toast.success(`Imported ${importable.length} models`);
    },
    [providers, fetchStates, updateDraft, setFetchState],
  );

  const save = React.useCallback(async () => {
    if (!draft) return;
    setBusy(true);
    try {
      await api.post<{ status: string }>("settings/replace", draft);
      setDirty(false);
      toast.success("Settings saved");
    } catch (error) {
      console.error("settings/replace failed", error);
      toast.error(error instanceof Error ? error.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }, [draft]);

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
          <div className="truncate text-sm font-medium">Providers & Models</div>
          <div className="truncate text-xs text-muted-foreground">
            Configure provider type, proxy, model fetch/import and model capabilities
          </div>
        </div>

        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setShowSecrets((value) => !value)}
          disabled={!draft}
          title={showSecrets ? "Hide secrets" : "Show secrets"}
        >
          {showSecrets ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
          {showSecrets ? "Hide" : "Show"}
        </Button>

        <Button type="button" variant="default" size="sm" onClick={() => void save()} disabled={busy || !draft}>
          <Save className="size-4" />
          Save
        </Button>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-5xl space-y-4 px-4 py-6">
            <div className="rounded-lg border p-4">
              <div className="flex items-center justify-between gap-2">
                <div>
                  <div className="text-sm font-semibold">Default Models</div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    Configure model and prompt used for automatic conversation title generation.
                  </div>
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => setDefaultModelsCollapsed((prev) => !prev)}
                  title={defaultModelsCollapsed ? "Expand default models" : "Collapse default models"}
                  aria-label={defaultModelsCollapsed ? "Expand default models" : "Collapse default models"}
                >
                  <ChevronRight className={`size-4 transition-transform ${defaultModelsCollapsed ? "" : "rotate-90"}`} />
                </Button>
              </div>

              {defaultModelsCollapsed ? null : (
                <div className="mt-4 rounded-md border p-3">
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex min-w-0 items-start gap-2">
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-sm"
                        className="mt-0.5 h-6 w-6"
                        onClick={() => setTitleSummaryCollapsed((prev) => !prev)}
                        title={titleSummaryCollapsed ? "Expand title summary model" : "Collapse title summary model"}
                        aria-label={titleSummaryCollapsed ? "Expand title summary model" : "Collapse title summary model"}
                      >
                        <ChevronRight className={`size-4 transition-transform ${titleSummaryCollapsed ? "" : "rotate-90"}`} />
                      </Button>
                      <div className="min-w-0">
                        <div className="text-sm font-medium">Title Summary Model</div>
                        <div className="mt-1 text-xs text-muted-foreground">
                          Used for first-reply auto title and regenerate-title action.
                        </div>
                      </div>
                    </div>
                  </div>

                  {titleSummaryCollapsed ? null : (
                    <>
                      <div className="mt-4 grid gap-3 md:grid-cols-2">
                        <div>
                          <div className="mb-1 text-xs font-medium">Model</div>
                          <Select value={titleModelSelectValue} onValueChange={updateTitleModel}>
                            <SelectTrigger className="w-full">
                              <SelectValue placeholder="Select model" />
                            </SelectTrigger>
                            <SelectContent>
                              {titleModelOptions.map((item) => (
                                <SelectItem key={item.id} value={item.id}>
                                  {item.label}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                      </div>

                      <div className="mt-4">
                        <div className="mb-1 flex items-center justify-between gap-2 text-xs font-medium">
                          <span>Title Summary Prompt</span>
                          <Button type="button" variant="outline" size="sm" onClick={resetTitlePrompt} disabled={busy}>
                            Reset Prompt
                          </Button>
                        </div>
                        <Textarea
                          value={titlePromptValue}
                          onChange={(event) => updateTitlePrompt(event.target.value)}
                          rows={8}
                          disabled={busy}
                          className="font-mono text-xs"
                        />
                        <div className="mt-2 text-xs text-muted-foreground">
                          Supported placeholders: {'{locale}'}, {'{content}'}
                        </div>
                      </div>
                    </>
                  )}
                </div>
              )}
            </div>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="text-sm font-semibold">Providers</div>
              <div className="flex items-center gap-2">
                <Select value={addProviderType} onValueChange={(value) => setAddProviderType(value as ProviderType)}>
                  <SelectTrigger className="w-40" size="sm">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {PROVIDER_TYPES.map((item) => (
                      <SelectItem key={item.value} value={item.value}>
                        {item.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button type="button" variant="secondary" size="sm" onClick={() => addProvider(addProviderType)} disabled={busy}>
                  <Plus className="size-4" />
                  Add Provider
                </Button>
              </div>
            </div>

            {providers.length === 0 ? (
              <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
                No providers configured.
              </div>
            ) : null}

            {providers.map((provider, providerIndex) => {
              const providerId = getString(provider.id);
              const providerKey = providerId || `provider-${providerIndex}`;
              const collapsed = collapsedProviders[providerKey] ?? true;
              const providerType = normalizeProviderType(provider.type);
              const models = ensureArray<AnyRecord>(provider.models);
              const chatSelectableModelCount = models.filter((model) => isChatSelectableModel(model)).length;
              const proxy = readProxy(provider);
              const fetchState = fetchStates[providerId];
              const selectedCount = fetchState ? Object.values(fetchState.selected).filter(Boolean).length : 0;

              return (
                <div key={providerId || providerIndex} className="rounded-lg border p-4">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="min-w-0 flex items-start gap-2">
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-sm"
                        className="mt-0.5 h-6 w-6"
                        onClick={() => toggleProviderCollapsed(providerKey)}
                        title={collapsed ? "Expand provider" : "Collapse provider"}
                        aria-label={collapsed ? "Expand provider" : "Collapse provider"}
                      >
                        <ChevronRight className={`size-4 transition-transform ${collapsed ? "" : "rotate-90"}`} />
                      </Button>
                      <div className="min-w-0">
                        <div className="text-sm font-semibold">{getString(provider.name, "(unnamed)")}</div>
                        <div className="mt-1 text-xs text-muted-foreground">
                          id: {providerId || "(missing)"} | models: {models.length} | chat: {chatSelectableModelCount} |{" "}
                          {provider.enabled !== false ? "enabled" : "disabled"}
                        </div>
                      </div>
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
                          void (async () => {
                            const confirmed = await confirm({
                              title: "Delete provider?",
                              description: "This will remove this provider and all of its models.",
                              confirmText: "Delete",
                              cancelText: "Cancel",
                              destructive: true,
                            });
                            if (!confirmed) return;
                            deleteProvider(providerIndex);
                          })();
                        }}
                        disabled={busy}
                        title="Delete provider"
                        aria-label="Delete provider"
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    </div>
                  </div>

                  {collapsed ? null : (
                    <>
                  <div className="mt-4 grid gap-3 md:grid-cols-2">
                    <div>
                      <div className="mb-1 text-xs font-medium">Name</div>
                      <Input
                        value={getString(provider.name)}
                        onChange={(event) => updateProvider(providerIndex, { name: event.target.value })}
                        disabled={busy}
                      />
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium">Type</div>
                      <Select value={providerType} onValueChange={(value) => updateProviderType(providerIndex, value as ProviderType)}>
                        <SelectTrigger className="w-full">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {PROVIDER_TYPES.map((item) => (
                            <SelectItem key={item.value} value={item.value}>
                              {item.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium">Base URL</div>
                      <Input
                        value={getString(provider.baseUrl)}
                        onChange={(event) => updateProvider(providerIndex, { baseUrl: event.target.value })}
                        disabled={busy}
                      />
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium">API Key</div>
                      <Input
                        type={showSecrets ? "text" : "password"}
                        value={getString(provider.apiKey)}
                        onChange={(event) => updateProvider(providerIndex, { apiKey: event.target.value })}
                        disabled={busy}
                        autoComplete="off"
                      />
                    </div>

                    {providerType === "openai" ? (
                      <>
                        <div>
                          <div className="mb-1 text-xs font-medium">Chat Completions Path</div>
                          <Input
                            value={getString(provider.chatCompletionsPath, "/chat/completions")}
                            onChange={(event) => updateProvider(providerIndex, { chatCompletionsPath: event.target.value })}
                            disabled={busy}
                          />
                        </div>
                        <div className="flex items-end justify-between rounded-md border px-3 py-2">
                          <div>
                            <div className="text-xs font-medium">Use Response API</div>
                            <div className="text-xs text-muted-foreground">OpenAI-compatible providers may not support this</div>
                          </div>
                          <Switch
                            checked={getBoolean(provider.useResponseApi)}
                            onCheckedChange={(checked) => updateProvider(providerIndex, { useResponseApi: checked })}
                            disabled={busy}
                          />
                        </div>
                      </>
                    ) : null}

                    {providerType === "google" ? (
                      <>
                        <div className="flex items-end justify-between rounded-md border px-3 py-2">
                          <div>
                            <div className="text-xs font-medium">Vertex AI</div>
                            <div className="text-xs text-muted-foreground">Enable Google Vertex AI mode</div>
                          </div>
                          <Switch
                            checked={getBoolean(provider.vertexAI)}
                            onCheckedChange={(checked) => updateProvider(providerIndex, { vertexAI: checked })}
                            disabled={busy}
                          />
                        </div>

                        {getBoolean(provider.vertexAI) ? (
                          <>
                            <div>
                              <div className="mb-1 text-xs font-medium">Project ID</div>
                              <Input
                                value={getString(provider.projectId)}
                                onChange={(event) => updateProvider(providerIndex, { projectId: event.target.value })}
                                disabled={busy}
                              />
                            </div>
                            <div>
                              <div className="mb-1 text-xs font-medium">Location</div>
                              <Input
                                value={getString(provider.location, "us-central1")}
                                onChange={(event) => updateProvider(providerIndex, { location: event.target.value })}
                                disabled={busy}
                              />
                            </div>
                            <div className="md:col-span-2">
                              <div className="mb-1 text-xs font-medium">Service Account Email</div>
                              <Input
                                value={getString(provider.serviceAccountEmail)}
                                onChange={(event) => updateProvider(providerIndex, { serviceAccountEmail: event.target.value })}
                                disabled={busy}
                              />
                            </div>
                            <div className="md:col-span-2">
                              <div className="mb-1 text-xs font-medium">Private Key</div>
                              <Input
                                type={showSecrets ? "text" : "password"}
                                value={getString(provider.privateKey)}
                                onChange={(event) => updateProvider(providerIndex, { privateKey: event.target.value })}
                                disabled={busy}
                                autoComplete="off"
                              />
                            </div>
                          </>
                        ) : null}
                      </>
                    ) : null}
                  </div>

                  <div className="mt-4 rounded-md border p-3">
                    <div className="text-xs font-semibold">Provider Proxy</div>
                    <div className="mt-2 grid gap-3 md:grid-cols-2">
                      <div>
                        <div className="mb-1 text-xs font-medium">Proxy Type</div>
                        <Select
                          value={proxy.type}
                          onValueChange={(value) => updateProviderProxy(providerIndex, { type: value as ProxyType })}
                        >
                          <SelectTrigger className="w-full">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {PROXY_TYPES.map((item) => (
                              <SelectItem key={item.value} value={item.value}>
                                {item.label}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>

                      {proxy.type !== "none" ? (
                        <>
                          <div>
                            <div className="mb-1 text-xs font-medium">Proxy Address</div>
                            <Input
                              value={proxy.address}
                              onChange={(event) => updateProviderProxy(providerIndex, { address: event.target.value })}
                              disabled={busy}
                              placeholder="127.0.0.1"
                            />
                          </div>
                          <div>
                            <div className="mb-1 text-xs font-medium">Proxy Port</div>
                            <Input
                              value={String(proxy.port || "")}
                              onChange={(event) => {
                                const parsed = Number.parseInt(event.target.value, 10);
                                updateProviderProxy(providerIndex, { port: Number.isFinite(parsed) ? parsed : 0 });
                              }}
                              disabled={busy}
                              placeholder="7890"
                            />
                          </div>
                          <div>
                            <div className="mb-1 text-xs font-medium">Proxy Username</div>
                            <Input
                              value={proxy.username}
                              onChange={(event) => updateProviderProxy(providerIndex, { username: event.target.value })}
                              disabled={busy}
                            />
                          </div>
                          <div>
                            <div className="mb-1 text-xs font-medium">Proxy Password</div>
                            <Input
                              type={showSecrets ? "text" : "password"}
                              value={proxy.password}
                              onChange={(event) => updateProviderProxy(providerIndex, { password: event.target.value })}
                              disabled={busy}
                              autoComplete="off"
                            />
                          </div>
                        </>
                      ) : (
                        <div className="self-end text-xs text-muted-foreground">Direct connection (no proxy)</div>
                      )}
                    </div>
                  </div>

                  <div className="mt-4 rounded-md border p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="text-xs font-semibold">Fetch Models from Provider API</div>
                      <div className="flex items-center gap-2">
                        <Button type="button" variant="outline" size="sm" onClick={() => void fetchProviderModels(providerIndex)}>
                          <Download className="size-4" />
                          {fetchState?.loading ? "Fetching..." : "Fetch"}
                        </Button>
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={() => importFetchedModels(providerIndex)}
                          disabled={!fetchState || selectedCount === 0}
                        >
                          Import Selected ({selectedCount})
                        </Button>
                      </div>
                    </div>

                    {fetchState?.error ? <div className="mt-2 text-xs text-destructive">{fetchState.error}</div> : null}

                    {fetchState && fetchState.models.length > 0 ? (
                      <div className="mt-3 max-h-56 space-y-2 overflow-auto rounded-md border p-2">
                        {fetchState.models.map((item) => (
                          <label key={item.modelId} className="flex cursor-pointer items-start gap-2 rounded px-2 py-1 text-xs hover:bg-muted/50">
                            <Checkbox
                              checked={Boolean(fetchState.selected[item.modelId])}
                              onCheckedChange={(checked) => toggleFetchedSelection(providerId, item.modelId, Boolean(checked))}
                            />
                            <div className="min-w-0">
                              <div className="truncate font-medium">{item.displayName}</div>
                              <div className="truncate text-muted-foreground">
                                {item.modelId} | {normalizeModelType(item.type)}
                              </div>
                            </div>
                          </label>
                        ))}
                      </div>
                    ) : null}
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
                      const modelType = normalizeModelType(model.type);
                      const inputModalities = ensureArray<string>(model.inputModalities);
                      const outputModalities = ensureArray<string>(model.outputModalities);
                      const abilities = ensureArray<string>(model.abilities);
                      const searchEnabled = hasBuiltInTool(model, "search");
                      const urlContextEnabled = hasBuiltInTool(model, "url_context");

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
                                void (async () => {
                                  const confirmed = await confirm({
                                    title: "Delete model?",
                                    description: "This will remove the model from this provider.",
                                    confirmText: "Delete",
                                    cancelText: "Cancel",
                                    destructive: true,
                                  });
                                  if (!confirmed) return;
                                  deleteModel(providerIndex, modelIndex);
                                })();
                              }}
                              disabled={busy}
                              title="Delete model"
                              aria-label="Delete model"
                            >
                              <Trash2 className="size-4" />
                            </Button>
                          </div>

                          <div className="mt-3 grid gap-3 md:grid-cols-2">
                            <div>
                              <div className="mb-1 text-xs font-medium">Display Name</div>
                              <Input
                                value={getString(model.displayName)}
                                onChange={(event) => updateModel(providerIndex, modelIndex, { displayName: event.target.value })}
                                disabled={busy}
                              />
                            </div>
                            <div>
                              <div className="mb-1 text-xs font-medium">Model ID</div>
                              <Input
                                value={getString(model.modelId)}
                                onChange={(event) => updateModel(providerIndex, modelIndex, { modelId: event.target.value })}
                                disabled={busy}
                              />
                            </div>
                            <div>
                              <div className="mb-1 text-xs font-medium">Model Type</div>
                              <Select
                                value={modelType}
                                onValueChange={(value) => updateModel(providerIndex, modelIndex, { type: normalizeModelType(value) })}
                              >
                                <SelectTrigger className="w-full">
                                  <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                  {MODEL_TYPES.map((item) => (
                                    <SelectItem key={item.value} value={item.value}>
                                      {item.label}
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                            </div>
                          </div>

                          <div className="mt-4 grid gap-4 md:grid-cols-2">
                            <div>
                              <div className="text-xs font-medium">Input Modalities</div>
                              <div className="mt-2 flex flex-wrap items-center gap-4">
                                {(["TEXT", "IMAGE"] as ModelModality[]).map((item) => (
                                  <label key={item} className="flex cursor-pointer items-center gap-2 text-sm">
                                    <Checkbox
                                      checked={inputModalities.includes(item)}
                                      onCheckedChange={(checked) =>
                                        updateModelArrayField(providerIndex, modelIndex, "inputModalities", item, Boolean(checked))
                                      }
                                    />
                                    <span>{item}</span>
                                  </label>
                                ))}
                              </div>
                            </div>
                            <div>
                              <div className="text-xs font-medium">Output Modalities</div>
                              <div className="mt-2 flex flex-wrap items-center gap-4">
                                {(["TEXT", "IMAGE"] as ModelModality[]).map((item) => (
                                  <label key={item} className="flex cursor-pointer items-center gap-2 text-sm">
                                    <Checkbox
                                      checked={outputModalities.includes(item)}
                                      onCheckedChange={(checked) =>
                                        updateModelArrayField(providerIndex, modelIndex, "outputModalities", item, Boolean(checked))
                                      }
                                    />
                                    <span>{item}</span>
                                  </label>
                                ))}
                              </div>
                            </div>
                            <div>
                              <div className="text-xs font-medium">Abilities</div>
                              <div className="mt-2 flex flex-wrap items-center gap-4">
                                {(["TOOL", "REASONING"] as ModelAbility[]).map((item) => (
                                  <label key={item} className="flex cursor-pointer items-center gap-2 text-sm">
                                    <Checkbox
                                      checked={abilities.includes(item)}
                                      onCheckedChange={(checked) =>
                                        updateModelArrayField(providerIndex, modelIndex, "abilities", item, Boolean(checked))
                                      }
                                    />
                                    <span>{item}</span>
                                  </label>
                                ))}
                              </div>
                            </div>
                            <div>
                              <div className="text-xs font-medium">Built-in Tools</div>
                              <div className="mt-2 flex flex-wrap items-center gap-4">
                                <label className="flex cursor-pointer items-center gap-2 text-sm">
                                  <Checkbox
                                    checked={searchEnabled}
                                    onCheckedChange={(checked) =>
                                      updateModelBuiltInTool(providerIndex, modelIndex, "search", Boolean(checked))
                                    }
                                  />
                                  <span>search</span>
                                </label>
                                <label className="flex cursor-pointer items-center gap-2 text-sm">
                                  <Checkbox
                                    checked={urlContextEnabled}
                                    onCheckedChange={(checked) =>
                                      updateModelBuiltInTool(providerIndex, modelIndex, "url_context", Boolean(checked))
                                    }
                                  />
                                  <span>url_context</span>
                                </label>
                              </div>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                    </>
                  )}
                </div>
              );
            })}

            {dirty ? <div className="text-xs text-muted-foreground">Unsaved changes.</div> : null}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}


