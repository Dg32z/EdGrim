import { api, ApiError } from './console.js';

const form = document.getElementById('login-form');
const errorNode = document.getElementById('login-error');
const submit = document.getElementById('login-submit');

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    errorNode.textContent = '';
    submit.disabled = true;

    try {
        await api.login(
            document.getElementById('login-username').value.trim(),
            document.getElementById('login-password').value,
        );
        window.location.assign('/admin/dashboard');
    } catch (error) {
        errorNode.textContent =
            error instanceof ApiError && error.status === 401
                ? '用户名或密码不正确'
                : error.detail || '登录失败';
        submit.disabled = false;
    }
});
