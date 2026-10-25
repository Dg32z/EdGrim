import { api, h, startPage } from './console.js';

async function load() {
    const [{ datasets }, { samples }] = await Promise.all([api.datasets(), api.samples()]);
    const sampleByTenant = new Map(samples.map((row) => [row.tenant_id, row]));
    const container = document.getElementById('dataset-table');

    if (datasets.length === 0) {
        container.replaceChildren(h('p', { class: 'empty', text: '没有租户，因此没有数据集。' }));
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
    );
}

startPage(load);
