import { api, confirmDialog, h, startPage, toast } from './console.js';

async function load() {
    const data = await api.models();
    const container = document.getElementById('model-table');

    document.getElementById('rollback-button').disabled = data.models.length < 2;

    if (data.models.length === 0) {
        container.replaceChildren(h('p', { class: 'empty', text: '尚未发布任何模型。' }));
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
                    h('th', { text: 'model_id' }),
                    h('th', { text: 'dataset_id' }),
                    h('th', { text: '后端' }),
                    h('th', { text: '阈值 cheat/legit' }),
                    h('th', { text: '状态' }),
                ),
            ),
            h('tbody', {}, rows),
        ),
    );
}

document.getElementById('rollback-button').addEventListener('click', async () => {
    if (!(await confirmDialog('回滚模型', '将把当前模型退回上一个版本。确定？', '回滚'))) return;
    try {
        const result = await api.rollback();
        toast(`已回滚到 ${result.restored}`);
        await load();
    } catch (error) {
        toast(error.detail || '回滚失败', 'error');
    }
});

startPage(load);
