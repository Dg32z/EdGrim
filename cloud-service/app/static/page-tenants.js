import {
    api,
    confirmDialog,
    h,
    openDialog,
    revealKey,
    shortTime,
    startPage,
    toast,
} from './console.js';

function tenantRow(tenant) {
    return h(
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
        h(
            'td',
            {},
            tenant.quota_per_minute === 0
                ? h('span', { class: 'chip', text: '不限' })
                : tenant.quota_per_minute,
        ),
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
                h('button', { class: 'btn btn--text btn--small', type: 'button', onclick: () => openEdit(tenant) }, '编辑'),
                tenant.revoked
                    ? null
                    : h('button', { class: 'btn btn--text btn--small', type: 'button', onclick: () => rotate(tenant) }, '轮换密钥'),
                tenant.revoked
                    ? null
                    : h(
                          'button',
                          { class: 'btn btn--text btn--small btn--danger', type: 'button', onclick: () => revoke(tenant) },
                          '吊销',
                      ),
                h(
                    'button',
                    { class: 'btn btn--text btn--small btn--danger', type: 'button', onclick: () => remove(tenant) },
                    '删除',
                ),
            ),
        ),
    );
}

function field(id, label, value = '', type = 'text', extra = {}) {
    return h(
        'label',
        { class: 'field' },
        h('input', { id, type, placeholder: ' ', value, ...extra }),
        h('span', { class: 'field__label', text: label }),
    );
}

function openCreate() {
    openDialog({
        title: '新建租户',
        body: h(
            'div',
            { class: 'stack' },
            field('tenant-id', '租户 ID'),
            field('tenant-servers', 'server-id（逗号分隔，留空=任意）'),
            field('tenant-quota', '配额（样本/分钟，0=不限）', '6000', 'number', { min: '0' }),
            h('p', { class: 'field__hint', text: '创建后会一次性显示 API key。' }),
        ),
        actions: [
            { label: '取消', value: null },
            { label: '创建', value: 'create', variant: 'filled' },
        ],
        onOpen: (dialog, close) => {
            dialog.querySelector('.dialog__actions .btn--filled').addEventListener(
                'click',
                async (event) => {
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
                        await load();
                    } catch (error) {
                        toast(error.detail || '创建失败', 'error');
                    }
                },
                true,
            );
        },
    });
}

function openEdit(tenant) {
    openDialog({
        title: `编辑 ${tenant.tenant_id}`,
        body: h(
            'div',
            { class: 'stack' },
            field('edit-servers', 'server-id（逗号分隔，留空=任意）', tenant.servers.join(',')),
            field(
                'edit-quota',
                '配额（样本/分钟，0=不限）',
                String(tenant.quota_per_minute),
                'number',
                { min: '0' },
            ),
        ),
        actions: [
            { label: '取消', value: null },
            { label: '保存', value: 'save', variant: 'filled' },
        ],
        onOpen: (dialog, close) => {
            dialog.querySelector('.dialog__actions .btn--filled').addEventListener(
                'click',
                async (event) => {
                    event.stopImmediatePropagation();
                    const servers = dialog
                        .querySelector('#edit-servers')
                        .value.split(',')
                        .map((part) => part.trim())
                        .filter(Boolean);
                    const quota = Number(
                        dialog.querySelector('#edit-quota').value || tenant.quota_per_minute,
                    );
                    try {
                        await api.updateTenant(tenant.tenant_id, { servers, quota_per_minute: quota });
                        close(null);
                        toast('已保存');
                        await load();
                    } catch (error) {
                        toast(error.detail || '保存失败', 'error');
                    }
                },
                true,
            );
        },
    });
}

async function rotate(tenant) {
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
        await load();
    } catch (error) {
        toast(error.detail || '轮换失败', 'error');
    }
}

async function revoke(tenant) {
    const ok = await confirmDialog(
        '吊销租户',
        `${tenant.tenant_id} 将无法再发送样本或请求裁决。样本数据保留。`,
        '吊销',
        true,
    );
    if (!ok) return;
    try {
        await api.revokeTenant(tenant.tenant_id);
        toast('已吊销');
        await load();
    } catch (error) {
        toast(error.detail || '吊销失败', 'error');
    }
}

async function remove(tenant) {
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
        await load();
    } catch (error) {
        toast(error.detail || '删除失败', 'error');
    }
}

async function load() {
    const { tenants } = await api.tenants();
    const container = document.getElementById('tenant-table');

    if (tenants.length === 0) {
        container.replaceChildren(h('p', { class: 'empty', text: '还没有租户。新建一个以获取 API key。' }));
        return;
    }

    container.replaceChildren(
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
            h('tbody', {}, tenants.map(tenantRow)),
        ),
    );
}

document.getElementById('create-tenant-button').addEventListener('click', openCreate);

startPage(load);
