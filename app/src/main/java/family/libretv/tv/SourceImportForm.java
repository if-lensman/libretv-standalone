package family.libretv.tv;

/** Phone-only form. Only the selected mode is serialized into the existing config field. */
final class SourceImportForm {
    private SourceImportForm() { }
    static String html(String action, String nonce) {
        return """
            <style>
            textarea{padding:14px;resize:vertical}
            #mode{border:0;padding:0;margin:24px 0}#mode legend{padding:0;margin-bottom:6px}
            #mode .mode-option{display:flex;align-items:center;gap:12px;margin:8px 0 0;padding:12px;
            min-height:48px;box-sizing:border-box;border:1px solid #48606e;border-radius:10px;background:#233543;cursor:pointer}
            #mode input{width:20px;height:20px;margin:0;padding:0;flex:0 0 20px;accent-color:#f15b9b}
            [hidden]{display:none!important}.hint{font-size:14px}#entries{padding-left:24px;overflow-wrap:anywhere}
            #entries li{margin:12px 0}#feedback{color:#ffbc9c}button.secondary{background:#233543;color:#e4eef6}
            </style>
            <h1>添加视频来源</h1>
            <p>选一种方式填写，发送后在电视上确认追加。</p>
            <form id="source-form" method="post" action="__ACTION__">
              <fieldset id="mode">
                <legend>录入方式</legend>
                <label class="mode-option"><input type="radio" name="entry-mode" value="manual" checked>逐条填写</label>
                <label class="mode-option"><input type="radio" name="entry-mode" value="text">导入文本</label>
                <label class="mode-option"><input type="radio" name="entry-mode" value="subscription">订阅地址</label>
              </fieldset>
              <section id="manual-panel">
                <label for="source-name">来源名称</label>
                <input id="source-name" placeholder="例如：我的影视源" maxlength="120" autocomplete="off">
                <label for="source-url">接口地址</label>
                <input id="source-url" type="url" placeholder="https://example.com/api.php/provide/vod/" autocapitalize="off" spellcheck="false">
                <p class="hint">填写 CMS JSON 采集接口地址。</p>
                <button class="secondary" id="append" type="button">追加一条</button>
                <p id="count" role="status">已追加 0 条</p>
                <ol id="entries"></ol>
                <p class="hint">可连续追加，最后统一发送。导入后可在电视的来源管理中删除或调整。</p>
              </section>
              <section id="text-panel" hidden>
                <label for="source-text">来源 JSON</label>
                <textarea id="source-text" rows="8" maxlength="100000" placeholder='{"sources":[{"name":"示例源","url":"https://example.com/api.php/provide/vod/"}]}' spellcheck="false"></textarea>
                <p class="hint">支持 sources 对象、来源数组或单个来源对象；TVBox 仅支持 type=1 的 CMS JSON 源，不支持 Spider/JAR。</p>
              </section>
              <section id="subscription-panel" hidden>
                <label for="subscription-url">订阅地址</label>
                <input id="subscription-url" type="url" placeholder="https://example.com/sources.json" autocapitalize="off" spellcheck="false">
                <p class="hint">订阅内容须为支持的来源 JSON。</p>
              </section>
              <p id="feedback" role="alert" hidden></p>
              <input id="config" name=config type="hidden">
              <button id="send" type="submit">发送到电视</button>
            </form>
            <p class="hint">手机与电视须在同一局域网；二维码 5 分钟有效，发送一次后失效。</p>
            <noscript><p>请使用支持 JavaScript 的浏览器打开此页面。</p></noscript>
            <script nonce="__NONCE__">
            (function () {
              var entries = [], form = document.getElementById('source-form');
              function el(id) { return document.getElementById(id); }
              function selectedMode() { return form.querySelector('input[name="entry-mode"]:checked').value; }
              function message(text) { el('feedback').textContent = text; el('feedback').hidden = !text; }
              function address(value) {
                var url;
                try { url = new URL(value); } catch (e) { throw new Error('请填写完整的 HTTP(S) 地址'); }
                if (!/^https?:$/.test(url.protocol) || !url.hostname || url.username || url.password || url.hash) {
                  throw new Error('请填写有效的 HTTP(S) 地址，不包含账号密码或 # 片段');
                }
                return value;
              }
              function append() {
                var name = el('source-name').value.trim(), url = el('source-url').value.trim();
                if (!name) throw new Error('请填写来源名称');
                address(url);
                if (entries.length >= 50) throw new Error('一次最多追加 50 条，请先发送');
                entries.push({name:name, url:url});
                var item = document.createElement('li'), title = document.createElement('strong'), detail = document.createElement('div');
                title.textContent = name; detail.textContent = url; item.appendChild(title); item.appendChild(detail); el('entries').appendChild(item);
                el('count').textContent = '已追加 ' + entries.length + ' 条';
                el('source-name').value = ''; el('source-url').value = ''; el('source-name').focus();
              }
              el('mode').addEventListener('change', function () {
                ['manual', 'text', 'subscription'].forEach(function (mode) {
                  var panel = el(mode + '-panel'), active = selectedMode() === mode;
                  panel.hidden = !active;
                  Array.prototype.forEach.call(panel.querySelectorAll('input,textarea,button'), function (input) { input.disabled = !active; });
                });
                message('');
              });
              el('append').addEventListener('click', function () { try { append(); message(''); } catch (e) { message(e.message); } });
              form.addEventListener('submit', function (event) {
                try {
                  var mode = selectedMode(), config;
                  if (mode === 'manual') {
                    if (el('source-name').value.trim() || el('source-url').value.trim()) append();
                    if (!entries.length) throw new Error('请先填写一条来源');
                    config = JSON.stringify({sources:entries});
                  } else if (mode === 'text') {
                    config = el('source-text').value.trim();
                    if (!config) throw new Error('请粘贴来源 JSON');
                    try { JSON.parse(config); } catch (e) { throw new Error('JSON 格式有误，请检查双引号、逗号和括号'); }
                  } else { config = address(el('subscription-url').value.trim()); }
                  if (encodeURIComponent(config).replace(/%20/g, '+').length + 7 > 131072) throw new Error('内容过长，请改用订阅地址');
                  el('config').value = config; el('send').disabled = true; message('');
                } catch (e) { event.preventDefault(); message(e.message); }
              });
            }());
            </script>
            """.replace("__ACTION__", action).replace("__NONCE__", nonce);
    }
}
