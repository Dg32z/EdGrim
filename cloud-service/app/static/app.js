// WatchNeko console.
//
// Everything is built with DOM nodes rather than innerHTML: tenant ids and key prefixes come from the
// server, and a console that assembles HTML strings out of them is one server-side change away from
// becoming an injection point. Building nodes makes that impossible by construction.
//
// The API is session-cookie authenticated, so every request is `credentials: 'same-origin'` and a 401 means
// "show the login screen", not "retry".

const API = '/admin/api';

// ---------------------------------------------------------------- tiny DOM helper

function h(tag, props = {}, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (value === null || value === undefined || value === false) continue;
        if (key === 'class') el.className = value;
        else if (key === 'text') el.textContent = value;
        else if (key === 'html') el.innerHTML = value; // only ever used with literal markup
        else if (key.startsWith('on')) el.addEventListener(key.slice(2).toLowerCase(), value);
        else el.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat()) {
        if (child === null || child === undefined || child === false) continue;
        el.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return el;
}

const icon = (name) => h('span', { class: 'material-symbols-outlined', 'aria-hidden': 'true', text: name });

// ---------------------------------------------------------------- api

class ApiError extends Error {
    constructor(status, detail) {
        super(detail || `HTTP ${status}`);
        this.status = status;
        this.detail = detail;
    }
}

async function request(path, options = {}) {
    const response = await fetch(`${API}${path}`, {
        credentials: 'same-origin',
        headers: options.body ? { 'content-type': 'application/json' } : {},
        ...options,
    });

    if (response.status === 401) throw new ApiError(401, '未登录或会话已过期');
    if (response.status === 204) return null;

    const text = await response.text();
    const body = text ? JSON.parse(text) : null;
    if (!response.ok) {
        throw new ApiError(response.status, (body && body.detail) || `HTTP ${response.status}`);
    }
    return body;
}

const api = {
    session: () => request('/session'),
    login: (username, password) =>
        request('/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
    logout: () => request('/logout', { method: 'POST' }),
    overview: () => request('/overview'),
    tenants: () => request('/tenants'),
    createTenant: (payload) => request('/tenants', { method: 'POST', body: JSON.stringify(payload) }),
    updateTenant: (id, payload) =>
        request(`/tenants/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify(payload) }),
    rotateTenant: (id) => request(`/tenants/${encodeURIComponent(id)}/rotate`, { method: 'POST' }),
    revokeTenant: (id) => request(`/tenants/${encodeURIComponent(id)}/revoke`, { method: 'POST' }),
    deleteTenant: (id) => request(`/tenants/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    datasets: () => request('/datasets'),
    samples: () => request('/samples'),
    models: () => request('/models'),
    rollback: () => request('/models/rollback', { method: 'POST' }),
    retrain: () => request('/retrain', { method: 'POST' }),
};

// ---------------------------------------------------------------- overlays

const overlayRoot = document.getElementById('overlay-root');
let snackbarTimer = null;

function toast(message, kind = 'info') {
    document.querySelectorAll('.snackbar').forEach((node) => node.remove());
    const node = h('div', { class: 'snackbar', role: 'status' }, message);
    if (kind === 'error') node.style.background = 'var(--md-sys-color-error)';
    if (kind === 'error') node.style.color = 'var(--md-sys-color-on-error)';
    overlayRoot.append(node);
    clearTimeout(snackbarTimer);
    snackbarTimer = setTimeout(() => node.remove(), 4200);
}

/**
 * Opens a modal and resolves with the value the caller asked for.
 * `actions` is a list of { label, value, variant } — the dialog closes with that value.
 */
function openDialog({ title, body, actions = [{ label: '关闭', value: null }], onOpen }) {
    return new Promise((resolve) => {
        const close = (value) => {
            scrim.remove();
            document.removeEventListener('keydown', onKey);
            resolve(value);
        };

        const onKey = (event) => {
            if (event.key === 'Escape') close(null);
        };

        const actionNodes = actions.map((action) =>
            h(
                'button',
                {
                    class: `btn btn--${action.variant || 'text'}`,
                    type: 'button',
                    onclick: () => close(action.value),
                },
                action.label,
            ),
        );

        const dialog = h(
            'div',
            { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
            h('h2', { class: 'dialog__title', text: title }),
            h('div', { class: 'dialog__body' }, body),
            h('div', { class: 'dialog__actions' }, actionNodes),
        );

        const scrim = h(
            'div',
            {
                class: 'scrim',
                onclick: (event) => {
                    if (event.target === scrim) close(null);
                },
            },
            dialog,
        );

        overlayRoot.append(scrim);
        document.addEventListener('keydown', onKey);
        if (onOpen) onOpen(dialog, close);
        const focusable = dialog.querySelector('input, select, button');
        if (focusable) focusable.focus();
    });
}

async function confirmDialog(title, message, confirmLabel, danger = false) {
    const value = await openDialog({
        title,
        body: h('p', { text: message }),
        actions: [
            { label: '取消', value: false },
            { label: confirmLabel, value: true, variant: danger ? 'danger' : 'filled' },
        ],
    });
    return value === true;
}

/** Shows a freshly minted API key. It is never retrievable again, so this dialog says so. */
async function revealKey(tenantId, apiKey) {
    const copyButton = h(
        'button',
        {
            class: 'btn btn--tonal',
            type: 'button',
            onclick: async () => {
                try {
                    await navigator.clipboard.writeText(apiKey);
                    toast('已复制到剪贴板');
                } catch {
                    toast('复制失败，请手动选中', 'error');
                }
            },
        },
        icon('content_copy'),
        '复制',
    );

    await openDialog({
        title: `${tenantId} 的 API key`,
        body: h(
            'div',
            {},
            h('p', {
                text: '这是该密钥唯一一次以明文显示。关闭后只能重新轮换，无法再查看。',
            }),
            h('div', { class: 'secret', text: apiKey }),
            h('p', { class: 'card__hint', text: '填入插件 config.yml 的 AnalysisA.cloud.api-key。' }),
            copyButton,
        ),
        actions: [{ label: '我已保存', value: true, variant: 'filled' }],
    });
}

// ---------------------------------------------------------------- views

const VIEWS = {
    dashboard: { title: '仪表盘', render: renderDashboard },
    tenants: { title: '租户', render: renderTenants },
    datasets: { title: '数据集', render: renderDatasets },
    models: { title: '模型', render: renderModels },
};

let currentView = 'dashboard';

function metricCard(label, value, hint) {
    return h(
        'div',
        { class: 'card' },
        h('span', { class: 'card__label', text: label }),
        h('span', { class: 'card__value', text: value }),
        hint ? h('span', { class: 'card__hint', text: hint }) : null,
    );
}

function verdictBar(verdicts) {
    const total = verdicts.cheat_like + verdicts.legit_like + verdicts.uncertain;
    if (total === 0) {
        return h('p', { class: 'card__hint', text: '尚无裁决。' });
    }
    const pct = (n) => `${(n / total) * 100}%`;
    return h(
        'div',
        { class: 'stack' },
        h(
            'div',
            { class: 'bar', role: 'img', 'aria-label': '裁决分布' },
            h('div', { class: 'bar__seg bar__seg--cheat', style: `width:${pct(verdicts.cheat_like)}` }),
            h('div', { class: 'bar__seg bar__seg--uncertain', style: `width:${pct(verdicts.uncertain)}` }),
            h('div', { class: 'bar__seg bar__seg--legit', style: `width:${pct(verdicts.legit_like)}` }),
        ),
        h(
            'div',
            { class: 'legend' },
            legendItem('--md-sys-color-error', `作弊样 ${verdicts.cheat_like}`),
            legendItem('--md-sys-color-tertiary', `不确定 ${verdicts.uncertain}`),
            legendItem('--md-sys-color-secondary', `正常样 ${verdicts.legit_like}`),
        ),
    );
}

function legendItem(colorVar, text) {
    return h(
        'span',
        {},
        h('span', { class: 'dot', style: `background: var(${colorVar})` }),
        text,
    );
}

async function renderDashboard(root) {
    const data = await api.overview();
    const metrics = data.metrics;

    const rollbackButton = h(
        'button',
        {
            class: 'btn btn--outlined',
            type: 'button',
            disabled: data.retained_versions < 2,
            title: data.retained_versions < 2 ? '没有可回滚的历史模型' : '',
            onclick: async () => {
                if (!(await confirmDialog('回滚模型', '将把当前模型退回上一个版本。确定？', '回滚'))) return;
                try {
                    const result = await api.rollback();
                    toast(`已回滚到 ${result.restored}`);
                    await refresh();
                } catch (error) {
                    toast(error.detail || '回滚失败', 'error');
                }
            },
        },
        icon('undo'),
        '回滚模型',
    );

    const retrainButton = h(
        'button',
        {
            class: 'btn btn--tonal',
            type: 'button',
            onclick: async () => {
                retrainButton.disabled = true;
                try {
                    const result = await api.retrain();
                    toast(
                        result.published
                            ? `已发布新模型 ${result.published}`
                            : `未发布：${result.skipped || '无变化'}`,
                    );
                    await refresh();
                } catch (error) {
                    toast(error.detail || '重训失败', 'error');
                } finally {
                    retrainButton.disabled = false;
                }
            },
        },
        icon('model_training'),
        '立即重训',
    );

    root.append(
        h(
            'div',
            { class: 'stack' },
            h(
                'div',
                { class: 'grid' },
                metricCard('当前模型', data.model ? data.model.model_id : '无', data.model ? data.model.dataset_id : '冷启动：检测不启用'),
                metricCard('样本总量', Object.values(metrics.samples_by_tenant).reduce((a, b) => a + b, 0)),
                metricCard('请求数', metrics.requests, `错误率 ${(metrics.error_rate * 100).toFixed(2)}%`),
                metricCard('队列积压', metrics.queue_backlog, '尚未被训练到的样本'),
                metricCard('租户', `${data.tenants.active} / ${data.tenants.total}`, '活跃 / 总数'),
                metricCard('回滚次数', metrics.rollbacks),
            ),
            h(
                'div',
                { class: 'card card--outlined' },
                h('span', { class: 'card__label', text: '裁决分布' }),
                verdictBar(metrics.verdicts),
            ),
            h(
                'div',
                { class: 'card card--outlined' },
                h(
                    'div',
                    { class: 'section-head' },
                    h('span', { class: 'section-head__title', text: '模型操作' }),
                    rollbackButton,
                    retrainButton,
                ),
                data.model
                    ? h(
                          'p',
                          { class: 'card__hint' },
                          `阈值 cheat ≥ ${data.model.thresholds.cheat_like}，legit ≤ ${data.model.thresholds.legit_like}，后端 ${data.model.trainer}。保留 ${data.retained_versions} 个版本。`,
                      )
                    : h('p', { class: 'card__hint', text: '尚未发布任何模型。先录制样本，再重训。' }),
            ),
        ),
    );
}

async function renderTenants(root) {
    const { tenants } = await api.tenants();

    const createButton = h(
        'button',
        { class: 'btn btn--filled', type: 'button', onclick: () => openCreateTenant() },
        icon('add'),
        '新建租户',
    );

    const header = h(
        'div',
        { class: 'section-head' },
        h('span', { class: 'section-head__title', text: '租户与密钥' }),
        createButton,
    );

    if (tenants.length === 0) {
        root.append(
            h(
                'div',
                { class: 'stack' },
                header,
                h('div', { class: 'card card--outlined' }, h('p', { class: 'empty', text: '还没有租户。新建一个以获取 API key。' })),
            ),
        );
        return;
    }

    const rows = tenants.map((tenant) =>
        h(
            'tr',
            {},
            h('td', {}, h('span', { class: 'mono', text: tenant.tenant_id })),
            h('td', {}, h('span', { class: 'mono', text: `${tenant.api_key_prefix}…` })),
            h(
                'td',
                {},
                tenant.servers.length === 0
                    ? h('span', { class: 'chip', text: '任意 server-id' })
                    : tenant.servers.map((server) => h('span', { class: 'chip', text: server })),
            ),
            h('td', { text: tenant.quota_per_minute }),
            h(
                'td',
                {},
                tenant.revoked
                    ? h('span', { class: 'chip chip--bad', text: '已吊销' })
                    : h('span', { class: 'chip chip--ok', text: '启用' }),
            ),
            h('td', { class: 'card__hint', text: tenant.last_used_at ? shortTime(tenant.last_used_at) : '从未使用' }),
            h(
                'td',
                {},
                h(
                    'div',
                    { class: 'row-actions' },
                    h(
                        'button',
                        { class: 'btn btn--text btn--small', type: 'button', onclick: () => openEditTenant(tenant) },
                        '编辑',
                    ),
                    tenant.revoked
                        ? null
                        : h(
                              'button',
                              {
                                  class: 'btn btn--text btn--small',
                                  type: 'button',
                                  onclick: () => rotateTenant(tenant),
                              },
                              '轮换密钥',
                          ),
                    tenant.revoked
                        ? null
                        : h(
                              'button',
                              {
                                  class: 'btn btn--text btn--small btn--danger',
                                  type: 'button',
                                  onclick: () => revokeTenant(tenant),
                              },
                              '吊销',
                          ),
                    h(
                        'button',
                        {
                            class: 'btn btn--text btn--small btn--danger',
                            type: 'button',
                            onclick: () => deleteTenant(tenant),
                        },
                        '删除',
                    ),
                ),
            ),
        ),
    );

    root.append(
        h(
            'div',
            { class: 'stack' },
            header,
            h(
                'div',
                { class: 'table-wrap' },
                h(
                    'table',
                    {},
                    h(
                        'thead',
                        {},
                        h(
                            'tr',
                            {},
                            h('th', { text: '租户' }),
                            h('th', { text: '密钥前缀' }),
                            h('th', { text: 'server-id' }),
                            h('th', { text: '配额/分钟' }),
                            h('th', { text: '状态' }),
                            h('th', { text: '最近使用' }),
                            h('th', { text: '' }),
                        ),
                    ),
                    h('tbody', {}, rows),
                ),
            ),
        ),
    );
}

async function renderDatasets(root) {
    const [{ datasets }, { samples }] = await Promise.all([api.datasets(), api.samples()]);
    const sampleByTenant = new Map(samples.map((row) => [row.tenant_id, row]));

    if (datasets.length === 0) {
        root.append(h('div', { class: 'card card--outlined' }, h('p', { class: 'empty', text: '没有租户，因此没有数据集。' })));
        return;
    }

    const rows = datasets.map((dataset) => {
        const stats = sampleByTenant.get(dataset.tenant_id) || { sessions: 0, players: 0 };
        return h(
            'tr',
            {},
            h('td', {}, h('span', { class: 'mono', text: dataset.tenant_id })),
            h('td', {}, h('span', { class: 'mono', text: dataset.dataset_id })),
            h('td', { text: dataset.samples }),
            h('td', { text: dataset.cheat }),
            h('td', { text: dataset.legit }),
            h('td', { text: stats.sessions }),
            h('td', { text: stats.players }),
            h(
                'td',
                {},
                dataset.trainable
                    ? h('span', { class: 'chip chip--ok', text: '可训练' })
                    : h('span', { class: 'chip chip--warn', text: '缺一类' }),
            ),
        );
    });

    root.append(
        h(
            'div',
            { class: 'stack' },
            h('div', { class: 'section-head' }, h('span', { class: 'section-head__title', text: '数据集版本' })),
            h(
                'div',
                { class: 'table-wrap' },
                h(
                    'table',
                    {},
                    h(
                        'thead',
                        {},
                        h(
                            'tr',
                            {},
                            h('th', { text: '租户' }),
                            h('th', { text: 'dataset_id' }),
                            h('th', { text: '样本' }),
                            h('th', { text: 'cheat' }),
                            h('th', { text: 'legit' }),
                            h('th', { text: '录制会话' }),
                            h('th', { text: '玩家' }),
                            h('th', { text: '状态' }),
                        ),
                    ),
                    h('tbody', {}, rows),
                ),
            ),
            h('p', {
                class: 'card__hint',
                text: 'dataset_id 由样本内容决定：样本不变则 id 不变，因此「数据变了吗」是一次字符串比较。',
            }),
        ),
    );
}

async function renderModels(root) {
    const data = await api.models();

    if (data.models.length === 0) {
        root.append(
            h(
                'div',
                { class: 'stack' },
                h('div', { class: 'card card--outlined' }, h('p', { class: 'empty', text: '尚未发布任何模型。' })),
            ),
        );
        return;
    }

    const rows = data.models.map((model) =>
        h(
            'tr',
            {},
            h('td', {}, h('span', { class: 'mono', text: model.model_id })),
            h('td', {}, h('span', { class: 'mono', text: model.dataset_id })),
            h('td', { text: model.trainer }),
            h('td', { text: `${model.cheat_like} / ${model.legit_like}` }),
            h(
                'td',
                {},
                model.model_id === data.current
                    ? h('span', { class: 'chip chip--ok', text: '当前' })
                    : model.rolled_back
                      ? h('span', { class: 'chip chip--warn', text: '已回滚' })
                      : h('span', { class: 'chip', text: '历史' }),
            ),
        ),
    );

    root.append(
        h(
            'div',
            { class: 'stack' },
            h('div', { class: 'section-head' }, h('span', { class: 'section-head__title', text: '模型历史' })),
            h(
                'div',
                { class: 'table-wrap' },
                h(
                    'table',
                    {},
                    h(
                        'thead',
                        {},
                        h(
                            'tr',
                            {},
                            h('th', { text: 'model_id' }),
                            h('th', { text: 'dataset_id' }),
                            h('th', { text: '后端' }),
                            h('th', { text: '阈值 cheat/legit' }),
                            h('th', { text: '状态' }),
                        ),
                    ),
                    h('tbody', {}, rows),
                ),
            ),
            h('p', {
                class: 'card__hint',
                text: '回滚不删除任何版本：被回滚的模型仍保留，可原样服务，这正是回滚安全的原因。',
            }),
        ),
    );
}

// ---------------------------------------------------------------- tenant actions

function openCreateTenant() {
    openDialog({
        title: '新建租户',
        body: h(
            'div',
            { class: 'stack' },
            h(
                'label',
                { class: 'field' },
                h('input', { id: 'tenant-id', type: 'text', placeholder: ' ', autocomplete: 'off' }),
                h('span', { class: 'field__label', text: '租户 ID' }),
            ),
            h(
                'label',
                { class: 'field' },
                h('input', { id: 'tenant-servers', type: 'text', placeholder: ' ', autocomplete: 'off' }),
                h('span', { class: 'field__label', text: 'server-id（逗号分隔，留空=任意）' }),
            ),
            h(
                'label',
                { class: 'field' },
                h('input', { id: 'tenant-quota', type: 'number', placeholder: ' ', value: '6000', min: '1' }),
                h('span', { class: 'field__label', text: '配额（样本/分钟）' }),
            ),
            h('p', { class: 'field__hint', text: '创建后会一次性显示 API key。' }),
        ),
        actions: [
            { label: '取消', value: null },
            { label: '创建', value: 'create', variant: 'filled' },
        ],
        onOpen: (dialog, close) => {
            const submit = dialog.querySelector('.dialog__actions .btn--filled');
            submit.addEventListener('click', async (event) => {
                event.stopImmediatePropagation();
                const tenantId = dialog.querySelector('#tenant-id').value.trim();
                if (!tenantId) {
                    toast('租户 ID 不能为空', 'error');
                    return;
                }
                const servers = dialog
                    .querySelector('#tenant-servers')
                    .value.split(',')
                    .map((part) => part.trim())
                    .filter(Boolean);
                const quota = Number(dialog.querySelector('#tenant-quota').value || 6000);

                try {
                    const result = await api.createTenant({
                        tenant_id: tenantId,
                        servers,
                        quota_per_minute: quota,
                    });
                    close(null);
                    await revealKey(result.tenant.tenant_id, result.api_key);
                    await refresh();
                } catch (error) {
                    toast(error.detail || '创建失败', 'error');
                }
            }, true);
        },
    });
}

function openEditTenant(tenant) {
    openDialog({
        title: `编辑 ${tenant.tenant_id}`,
        body: h(
            'div',
            { class: 'stack' },
            h(
                'label',
                { class: 'field' },
                h('input', {
                    id: 'edit-servers',
                    type: 'text',
                    placeholder: ' ',
                    value: tenant.servers.join(','),
                }),
                h('span', { class: 'field__label', text: 'server-id（逗号分隔，留空=任意）' }),
            ),
            h(
                'label',
                { class: 'field' },
                h('input', {
                    id: 'edit-quota',
                    type: 'number',
                    placeholder: ' ',
                    min: '1',
                    value: String(tenant.quota_per_minute),
                }),
                h('span', { class: 'field__label', text: '配额（样本/分钟）' }),
            ),
        ),
        actions: [
            { label: '取消', value: null },
            { label: '保存', value: 'save', variant: 'filled' },
        ],
        onOpen: (dialog, close) => {
            const submit = dialog.querySelector('.dialog__actions .btn--filled');
            submit.addEventListener('click', async (event) => {
                event.stopImmediatePropagation();
                const servers = dialog
                    .querySelector('#edit-servers')
                    .value.split(',')
                    .map((part) => part.trim())
                    .filter(Boolean);
                const quota = Number(dialog.querySelector('#edit-quota').value || tenant.quota_per_minute);

                try {
                    await api.updateTenant(tenant.tenant_id, {
                        servers,
                        quota_per_minute: quota,
                    });
                    close(null);
                    toast('已保存');
                    await refresh();
                } catch (error) {
                    toast(error.detail || '保存失败', 'error');
                }
            }, true);
        },
    });
}

async function rotateTenant(tenant) {
    const ok = await confirmDialog(
        '轮换密钥',
        `为 ${tenant.tenant_id} 生成新密钥。旧密钥将立即失效，正在使用它的服务器会立刻开始 401。`,
        '轮换',
        true,
    );
    if (!ok) return;

    try {
        const result = await api.rotateTenant(tenant.tenant_id);
        await revealKey(result.tenant.tenant_id, result.api_key);
        await refresh();
    } catch (error) {
        toast(error.detail || '轮换失败', 'error');
    }
}

async function revokeTenant(tenant) {
    const ok = await confirmDialog(
        '吊销租户',
        `${tenant.tenant_id} 将无法再发送样本或请求裁决。样本数据保留，可随时重新查看。`,
        '吊销',
        true,
    );
    if (!ok) return;

    try {
        await api.revokeTenant(tenant.tenant_id);
        toast('已吊销');
        await refresh();
    } catch (error) {
        toast(error.detail || '吊销失败', 'error');
    }
}

async function deleteTenant(tenant) {
    const ok = await confirmDialog(
        '删除租户',
        `永久删除 ${tenant.tenant_id} 的租户记录。它的样本不会一并删除，但将不再有归属。`,
        '删除',
        true,
    );
    if (!ok) return;

    try {
        await api.deleteTenant(tenant.tenant_id);
        toast('已删除');
        await refresh();
    } catch (error) {
        toast(error.detail || '删除失败', 'error');
    }
}

// ---------------------------------------------------------------- shell

const content = document.getElementById('content');
const viewTitle = document.getElementById('view-title');
const loginView = document.getElementById('login-view');
const appView = document.getElementById('app-view');

async function render(view = currentView) {
    currentView = view;
    viewTitle.textContent = VIEWS[view].title;
    document.querySelectorAll('.rail__item').forEach((item) => {
        if (item.dataset.view === view) item.setAttribute('aria-current', 'page');
        else item.removeAttribute('aria-current');
    });

    content.replaceChildren();
    try {
        await VIEWS[view].render(content);
    } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
            showLogin();
            return;
        }
        content.append(
            h(
                'div',
                { class: 'card card--outlined' },
                h('p', { class: 'empty', text: `加载失败：${error.detail || error.message}` }),
            ),
        );
    }
}

async function refresh() {
    await render(currentView);
}

function showLogin() {
    appView.hidden = true;
    loginView.hidden = false;
    document.getElementById('login-username').focus();
}

function showApp() {
    loginView.hidden = true;
    appView.hidden = false;
    render('dashboard');
}

// ---------------------------------------------------------------- wiring

document.querySelectorAll('.rail__item').forEach((item) => {
    item.addEventListener('click', () => render(item.dataset.view));
});

document.getElementById('refresh-button').addEventListener('click', refresh);

document.getElementById('logout-button').addEventListener('click', async () => {
    try {
        await api.logout();
    } catch {
        // Signing out is best-effort: the cookie is cleared server-side or it expires.
    }
    showLogin();
});

document.getElementById('login-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const errorNode = document.getElementById('login-error');
    const submit = document.getElementById('login-submit');
    errorNode.textContent = '';
    submit.disabled = true;

    try {
        await api.login(
            document.getElementById('login-username').value.trim(),
            document.getElementById('login-password').value,
        );
        document.getElementById('login-password').value = '';
        showApp();
    } catch (error) {
        errorNode.textContent = error.status === 401 ? '用户名或密码不正确' : error.detail || '登录失败';
    } finally {
        submit.disabled = false;
    }
});

function shortTime(iso) {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    return date.toLocaleString();
}

// On load, ask the server whether a session already exists rather than assuming either way.
(async () => {
    try {
        await api.session();
        showApp();
    } catch {
        showLogin();
    }
})();
