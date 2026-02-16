import * as React from 'react';

import { Link } from 'react-router';
import { ChevronLeft, Home, Plus, Save } from 'lucide-react';
import { toast } from 'sonner';
import { v4 as uuidv4 } from 'uuid';

import { Button } from '~/components/ui/button';
import { ScrollArea } from '~/components/ui/scroll-area';
import { Textarea } from '~/components/ui/textarea';
import api from '~/services/api';
import { useSettingsStore } from '~/stores';

export function meta() {
  return [{ title: 'Settings - MCP' }];
}

type AnyRecord = Record<string, any>;

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '';
  }
}

function parseJson(raw: string): unknown {
  const text = raw.trim();
  if (text.length === 0) return null;
  return JSON.parse(text) as unknown;
}

function defaultMcpServer(): AnyRecord {
  return {
    id: uuidv4(),
    type: 'streamable_http',
    url: 'http://127.0.0.1:3000/mcp',
    commonOptions: {
      enable: true,
      name: 'MCP Server',
      headers: [],
      tools: [
        {
          enable: true,
          name: 'tool',
          description: '',
          needsApproval: false,
          inputSchema: { type: 'object', properties: {}, required: [] },
        },
      ],
    },
  };
}

export default function SettingsMcpPage() {
  const settings = useSettingsStore((s) => s.settings) as AnyRecord | null;
  const [busy, setBusy] = React.useState(false);
  const [dirty, setDirty] = React.useState(false);
  const [text, setText] = React.useState(() => safeStringify(settings?.mcpServers ?? []));

  React.useEffect(() => {
    if (dirty) return;
    setText(safeStringify(settings?.mcpServers ?? []));
  }, [settings?.mcpServers, dirty]);

  const addServer = React.useCallback(() => {
    let current: unknown;
    try {
      current = parseJson(text);
    } catch {
      toast.error('Invalid JSON');
      return;
    }

    const list = Array.isArray(current) ? current.slice() : [];
    list.push(defaultMcpServer());
    setText(JSON.stringify(list, null, 2));
    setDirty(true);
  }, [text]);

  const save = React.useCallback(async () => {
    if (!settings) return;

    let next: unknown;
    try {
      next = parseJson(text);
    } catch {
      toast.error('Invalid JSON');
      return;
    }

    if (!Array.isArray(next)) {
      toast.error('mcpServers must be a JSON array');
      return;
    }

    setBusy(true);
    try {
      const nextSettings = { ...settings, mcpServers: next };
      await api.post<{ status: string }>('settings/replace', nextSettings);
      toast.success('Settings saved');
      setDirty(false);
    } catch (error) {
      console.error('settings/replace failed', error);
      toast.error(error instanceof Error ? error.message : 'Save failed');
    } finally {
      setBusy(false);
    }
  }, [settings, text]);

  return (
    <div className='flex h-svh flex-col bg-background'>
      <div className='flex items-center gap-2 border-b px-4 py-3'>
        <Button asChild variant='outline' size='icon-sm' title='Back to settings' aria-label='Back to settings'>
          <Link to='/settings'>
            <ChevronLeft className='size-4' />
          </Link>
        </Button>
        <Button asChild variant='outline' size='icon-sm' title='Back to chats' aria-label='Back to chats'>
          <Link to='/'>
            <Home className='size-4' />
          </Link>
        </Button>
        <div className='min-w-0 flex-1'>
          <div className='truncate text-sm font-medium'>MCP</div>
          <div className='truncate text-xs text-muted-foreground'>
            Edit settings.mcpServers as raw JSON (portable backend)
          </div>
        </div>

        <Button type='button' variant='secondary' size='sm' onClick={addServer} disabled={busy}>
          <Plus className='size-4' />
          Add Server
        </Button>
        <Button
          type='button'
          variant='default'
          size='sm'
          onClick={() => void save()}
          disabled={busy || !settings}
        >
          <Save className='size-4' />
          Save
        </Button>
      </div>

      <div className='min-h-0 flex-1'>
        <ScrollArea className='h-full'>
          <div className='mx-auto w-full max-w-4xl space-y-4 px-4 py-6'>
            <div className='rounded-lg border p-4'>
              <div className='text-sm font-semibold'>mcpServers</div>
              <div className='mt-1 text-xs text-muted-foreground'>
                Backend reads: server.id, server.type, server.url, commonOptions(enable/name/headers/tools).
              </div>

              <Textarea
                value={text}
                onChange={(e) => {
                  setText(e.target.value);
                  setDirty(true);
                }}
                className='mt-3 min-h-[60vh] font-mono text-xs'
                spellCheck={false}
              />

              <div className='mt-2 text-xs text-muted-foreground'>
                Tips: set tool.needsApproval=true to require manual approval during tool calls.
              </div>
            </div>

            <div className='rounded-lg border p-4 text-xs text-muted-foreground'>
              This is a minimal editor. Use Settings - Advanced for full settings.json editing.
            </div>

            {dirty ? (
              <div className='text-xs text-muted-foreground'>Unsaved changes.</div>
            ) : null}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}