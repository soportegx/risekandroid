const $ = (id) => document.getElementById(id);

let currentUser = null;
let currentLocal = null;
let currentCaja = null;
let loginCajas = [];
let clockTimer = null;
let sessionTimer = null;
let lastSessionTouch = 0;
let menuRendered = false;
let clienteCatalogos = null;
let productoCatalogos = null;
let posState = null;
let posKeyHandler = null;
let nvState = null;

const SESSION_KEY = "risek.activeSession.v1";
const SESSION_IDLE_MS = 12 * 60 * 60 * 1000;
const SESSION_TOUCH_MS = 60 * 1000;

function sessionStores() {
  return [localStorage, sessionStorage];
}

function clearStoredSession() {
  for (const storage of sessionStores()) storage.removeItem(SESSION_KEY);
}

function readStoredSession() {
  for (const storage of sessionStores()) {
    try {
      const raw = storage.getItem(SESSION_KEY);
      if (!raw) continue;
      const saved = JSON.parse(raw);
      if (!saved?.user || !saved?.local || !saved?.caja || Number(saved.expiresAt || 0) <= Date.now()) {
        storage.removeItem(SESSION_KEY);
        continue;
      }
      return { saved, storage };
    } catch (_) {
      storage.removeItem(SESSION_KEY);
    }
  }
  return null;
}

function storeSession(remember = true) {
  if (!currentUser || !currentLocal || !currentCaja) return;
  const storage = remember ? localStorage : sessionStorage;
  const otherStorage = remember ? sessionStorage : localStorage;
  otherStorage.removeItem(SESSION_KEY);
  storage.setItem(SESSION_KEY, JSON.stringify({
    user: currentUser,
    local: currentLocal,
    caja: currentCaja,
    remember,
    expiresAt: Date.now() + SESSION_IDLE_MS
  }));
  lastSessionTouch = Date.now();
}

function touchSession() {
  if (!currentUser || Date.now() - lastSessionTouch < SESSION_TOUCH_MS) return;
  const stored = readStoredSession();
  storeSession(stored?.saved?.remember !== false);
}

function stopSessionControl() {
  if (sessionTimer) clearInterval(sessionTimer);
  sessionTimer = null;
}

function startSessionControl() {
  stopSessionControl();
  sessionTimer = setInterval(() => {
    if (!currentUser) return;
    const stored = readStoredSession();
    if (!stored) logoutApp("La sesión finalizó por inactividad.");
  }, 60 * 1000);
}

function restoreSession() {
  const stored = readStoredSession();
  if (!stored) return false;
  enterApp(stored.saved.user, stored.saved.local, stored.saved.caja, false);
  touchSession();
  return true;
}

const menu = [
  {
    title: "Home",
    icon: "DB",
    items: [
      { id: "home", label: "Dashboard", icon: "DB" }
    ]
  },
  {
    title: "Parametros",
    icon: "PR",
    items: [
      { id: "usuarios", label: "Usuarios", table: "secuser" },
      { id: "roles", label: "Roles y permisos", special: "roles" },
      { id: "locales", label: "Locales", table: "locales" },
      { id: "bancos", label: "Bancos", table: "bancos" },
      { id: "productos", label: "Productos", table: "productos" },
      { id: "clientes", label: "Clientes", table: "clientes" },
      { id: "vendedores", label: "Vendedores", table: "vendedores" },
      { id: "proveedores", label: "Proveedores", table: "proveedores" },
      { id: "cajas", label: "Cajas", table: "caja" },
      { id: "rutas", label: "Rutas", table: "rutas" },
      { id: "ciudades", label: "Ciudades", table: "ciudades" },
      { id: "bodegas", label: "Bodegas", table: "bodegas" },
      { id: "familias", label: "Familias", table: "familias" },
      { id: "unidades", label: "Unidades", table: "unidades" },
      { id: "formasdepago", label: "Formas de pago", table: "formasdepago" },
      { id: "listaprecios", label: "Listas de precios", table: "listaprecios" }
    ]
  },
  {
    title: "Ventas",
    icon: "VE",
    items: [
      { id: "punto-venta", label: "Punto de venta", special: "pos" },
      { id: "facturas", label: "Facturas", table: "ventas", doc: "FE / FA", special: "facturas" },
      { id: "notas-venta", label: "Notas de venta", table: "ventas", doc: "NV", special: "notas-venta" },
      { id: "guias", label: "Guias", table: "ventas", doc: "GD" },
      { id: "notas-credito", label: "Notas de Credito", table: "ventas", doc: "NC" },
      { id: "boletas", label: "Boletas", table: "ventas", doc: "BO", special: "boletas" },
      { id: "picking", label: "Picking Comercial", special: "picking" }
    ]
  },
  {
    title: "Compras",
    icon: "CP",
    items: [
      { id: "compras", label: "Documentos de compra", table: "compras", special: "purchases" },
      { id: "recepcion", label: "Recepcion y cierre", table: "compraslevel1", special: "purchases", purchaseStatus: "A" },
      { id: "proveedor-cuenta", label: "Cuenta proveedor", table: "proveedores" }
    ]
  },
  {
    title: "Reporte",
    icon: "RP",
    items: [
      { id: "rep-estadisticas", label: "Estadisticas de ventas", special: "report", report: "estadisticas" },
      { id: "rep-vendedores", label: "Ventas por vendedor", special: "report", report: "vendedores" },
      { id: "rep-rutas", label: "Ventas por rutas", special: "report", report: "rutas" },
      { id: "rep-familias", label: "Ventas por familias", special: "report", report: "familias" },
      { id: "rep-formas-pago", label: "Formas de pago", special: "report", report: "formas-pago" },
      { id: "rep-productos", label: "Ranking de productos", special: "report", report: "productos" },
      { id: "rep-pendientes", label: "Facturas pendientes", special: "report", report: "pendientes" },
      { id: "rep-cta-cte", label: "Cuentas corrientes", special: "report", report: "cta-cte" },
      { id: "rep-cobros", label: "Cobros y recaudacion", special: "report", report: "cobros" },
      { id: "rep-cartola", label: "Cartola clientes", special: "report", report: "cartola" },
      { id: "rep-compras", label: "Compras por proveedor", special: "report", report: "compras" },
      { id: "rep-inventario", label: "Inventario valorizado", special: "report", report: "inventario" },
      { id: "rep-stock-bajo", label: "Stock critico", special: "report", report: "stock-bajo" },
      { id: "reporte-picking", label: "Picking por ruta", special: "picking" }
    ]
  },
  {
    title: "Gerencia",
    icon: "GE",
    items: [
      { id: "gerencia-kpi", label: "Indicadores KPI", special: "management", management: "kpi" },
      { id: "gerencia-margen", label: "Margenes", special: "management", management: "margen" },
      { id: "gerencia-rutas", label: "Rendimiento rutas", special: "management", management: "rutas" },
      { id: "gerencia-caja", label: "Caja y bancos", special: "management", management: "caja" }
    ]
  }
];

const moduleLookup = new Map(menu.flatMap(group => group.items.map(item => [item.id, { ...item, group: group.title }])));

function clNumber(value, decimals = 2) {
  return Number(value || 0).toLocaleString("es-CL", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  });
}

function money(value) {
  return Number(value || 0).toLocaleString("es-CL", {
    style: "currency",
    currency: "CLP",
    maximumFractionDigits: 0
  });
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function setLoginMessage(message, type = "") {
  $("loginMessage").textContent = message || "";
  $("loginMessage").className = `login-message ${type}`.trim();
}

function setToday() {
  const date = new Date();
  $("todayLabel").textContent = date.toLocaleDateString("es-CL", {
    weekday: "short",
    day: "2-digit",
    month: "short"
  });
}

async function loadLoginUsers() {
  const userSelect = $("loginUser");
  const localSelect = $("loginLocal");
  const cajaSelect = $("loginCaja");
  userSelect.innerHTML = `<option value="">Cargando usuarios...</option>`;
  localSelect.innerHTML = `<option value="">Cargando locales...</option>`;
  cajaSelect.innerHTML = `<option value="">Cargando cajas...</option>`;
  setLoginMessage("Conectando con locales, cajas y usuarios...", "info");

  const [users, locales, cajas] = await Promise.all([
    fetch("/api/secusers").then(r => r.json()).catch(() => []),
    fetch("/api/locales/login").then(r => r.json()).catch(() => []),
    fetch("/api/cajas/login").then(r => r.json()).catch(() => [])
  ]);
  loginCajas = cajas;
  userSelect.innerHTML = "";
  localSelect.innerHTML = "";
  cajaSelect.innerHTML = `<option value="">Seleccione primero un local</option>`;
  cajaSelect.disabled = true;

  if (!users.length) {
    userSelect.innerHTML = `<option value="">No hay usuarios disponibles</option>`;
    setLoginMessage("No se pudieron cargar usuarios desde secuser. Revise la base de datos.", "error");
    return;
  }

  userSelect.appendChild(new Option("Seleccione usuario", ""));
  for (const user of users) {
    const label = `${user.user_name || "Usuario"}${user.vendedor_codigo ? " / Vendedor " + user.vendedor_codigo : ""}`;
    const opt = new Option(label, user.user_id);
    opt.dataset.userName = user.user_name || "";
    userSelect.appendChild(opt);
  }
  localSelect.appendChild(new Option("Seleccione local", ""));
  for (const local of locales) {
    const label = `${local.local_codigo} · ${local.local_descripcion || "Local"}`;
    localSelect.appendChild(new Option(label, local.local_codigo));
  }
  if (!locales.length) {
    localSelect.innerHTML = `<option value="">No hay locales disponibles</option>`;
    setLoginMessage("No se pudieron cargar locales desde la base de datos.", "error");
    return;
  }
  localSelect.onchange = () => loadLoginCajas(localSelect.value);
  setLoginMessage(`${users.length} usuarios, ${locales.length} locales y ${cajas.length} cajas disponibles`, "ok");
}

function loadLoginCajas(localCodigo) {
  const select = $("loginCaja");
  const cajas = loginCajas.filter(item => String(item.local_codigo).trim() === String(localCodigo).trim());
  select.innerHTML = `<option value="">Seleccione caja</option>`;
  cajas.forEach(item => select.appendChild(new Option(`Caja ${item.caja_codigo}`, item.caja_codigo)));
  select.disabled = !localCodigo || !cajas.length;
  if (cajas.length === 1) select.value = String(cajas[0].caja_codigo);
}

function renderMenu() {
  const allowed = new Set(currentUser?.permissions || []);
  const visibleMenu = menu.map(group => ({...group, items: group.items.filter(item => allowed.has(item.id))})).filter(group => group.items.length);
  $("mainMenu").innerHTML = visibleMenu.map((group, groupIndex) => `
    <div class="menu-group ${groupIndex === 0 ? "open" : ""}">
      <button class="menu-heading" type="button" data-menu-group>
        <span class="menu-icon">${group.icon}</span>
        <span>${group.title}</span>
        <b>+</b>
      </button>
      <div class="menu-children">
        ${group.items.map(item => `
        <button class="menu-item" type="button" data-view="${item.id}">
          <span class="menu-dot"></span>
          <span>${item.label}</span>
        </button>
        `).join("")}
      </div>
    </div>
  `).join("");

  if (!menuRendered) $("mainMenu").addEventListener("click", (event) => {
    const heading = event.target.closest("[data-menu-group]");
    if (heading) {
      const group = heading.closest(".menu-group");
      const opening = !group.classList.contains("open");
      document.querySelectorAll(".menu-group").forEach(item => item.classList.remove("open"));
      group.classList.toggle("open", opening);
      return;
    }
    const button = event.target.closest("[data-view]");
    if (!button) return;
    navigate(button.dataset.view);
    document.querySelector(".sidebar").classList.remove("open");
  });
  menuRendered = true;
}

function setActive(view) {
  for (const button of document.querySelectorAll("[data-view]")) {
    button.classList.toggle("active", button.dataset.view === view);
    if (button.dataset.view === view) {
      document.querySelectorAll(".menu-group").forEach(group => group.classList.remove("open"));
      button.closest(".menu-group")?.classList.add("open");
    }
  }
}

function navigate(view) {
  if (posKeyHandler) {
    document.removeEventListener("keydown", posKeyHandler);
    posKeyHandler = null;
  }
  const item = moduleLookup.get(view) || moduleLookup.get("home");
  $("content").classList.toggle("powerbi-home", item.id === "home");
  setActive(item.id);
  $("pageTitle").textContent = item.label;

  if (item.id === "home") {
    renderHome();
    return;
  }
  if (item.special === "future") {
    renderFutureSystem();
    return;
  }
  if (item.special === "picking") {
    renderPicking();
    loadCombos();
    return;
  }
  if (item.special === "pos") {
    renderPos();
    return;
  }
  if (item.special === "boletas") {
    renderBoletas();
    return;
  }
  if (item.special === "facturas") {
    renderFacturas();
    return;
  }
  if (item.special === "notas-venta") {
    renderNotasVenta();
    return;
  }
  if (item.special === "roles") {
    renderRoles();
    return;
  }
  if (item.special === "report") {
    renderReportCenter(item.report);
    return;
  }
  if (item.special === "management") {
    renderManagement(item.management);
    return;
  }
  if (item.special === "purchases") {
    renderPurchases(item.purchaseStatus || "");
    return;
  }
  if (item.id === "clientes") {
    renderClientes();
    return;
  }
  if (item.id === "productos") {
    renderProductos();
    return;
  }
  if (item.group === "Parametros") {
    renderProfessionalMaintainer(item);
    return;
  }
  renderModule(item);
}

function renderFutureSystem() {
  $("content").innerHTML = `
    <section class="future-hero">
      <div>
        <p class="eyebrow">Mockup futuro</p>
        <h2>RISEK Suite Comercial 360</h2>
        <p>Una vision completa del sistema: menu agil, paneles por rol, accesos rapidos, mantenedores conectados y operacion diaria desde un centro de mando.</p>
      </div>
      <div class="future-version">
        <strong>v2.0</strong>
        <span>ERP Web Comercial</span>
      </div>
    </section>

    <section class="quick-ribbon">
      ${quickAction("NV", "Nueva venta", "Factura, boleta o guia", "facturas")}
      ${quickAction("PK", "Picking", "Generar PDF por ruta", "picking")}
      ${quickAction("CL", "Cliente", "Buscar o editar ficha", "clientes")}
      ${quickAction("PR", "Producto", "Precio, stock y familia", "productos")}
      ${quickAction("CP", "Compra", "Recepcion y proveedor", "compras")}
      ${quickAction("GE", "Gerencia", "Indicadores y margen", "gerencia-kpi")}
    </section>

    <section class="future-layout">
      <article class="future-panel module-map-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Menu futuro</p>
            <h2>Mapa modular completo</h2>
            <p>Estructura sugerida para crecer por areas, con accesos de alto uso siempre visibles.</p>
          </div>
        </div>
        <div class="system-map">
          ${futureGroup("Parametros", ["Usuarios y perfiles", "Clientes", "Productos", "Proveedores", "Rutas", "Bancos", "Locales", "Cajas"])}
          ${futureGroup("Ventas", ["Facturas", "Boletas", "Guias", "Notas de credito", "Cotizaciones", "Pedidos", "Picking"])}
          ${futureGroup("Compras", ["Ordenes", "Recepcion", "Costos", "Cuenta proveedor", "Documentos pendientes"])}
          ${futureGroup("Bodega", ["Stock", "Inventario", "Transferencias", "Ubicaciones", "Criticos"])}
          ${futureGroup("Reportes", ["Ventas por periodo", "Stock valorizado", "Clientes", "Rutas", "Compras", "PDF/CSV"])}
          ${futureGroup("Gerencia", ["KPI diario", "Margen", "Caja y bancos", "Vendedores", "Alertas"])}
        </div>
      </article>

      <aside class="future-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Trabajo diario</p>
            <h2>Cola operativa</h2>
          </div>
        </div>
        <div class="work-queue">
          ${queueItem("Facturas listas para picking", "32 documentos", "Alta")}
          ${queueItem("Clientes sin ruta asignada", "8 fichas", "Media")}
          ${queueItem("Productos bajo stock critico", "14 SKU", "Alta")}
          ${queueItem("Compras por recepcionar", "5 OC", "Media")}
          ${queueItem("Guias pendientes de despacho", "11 docs", "Alta")}
        </div>
      </aside>
    </section>

    <section class="future-layout lower">
      <article class="future-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Paneles por rol</p>
            <h2>Experiencia segun usuario</h2>
          </div>
        </div>
        <div class="role-grid">
          ${roleCard("Administrador", "Usuarios, parametros, auditoria, permisos y respaldos.")}
          ${roleCard("Ventas", "Clientes, documentos, notas de credito, precios y cuenta corriente.")}
          ${roleCard("Bodega", "Picking, rutas, stock, transferencias e inventario.")}
          ${roleCard("Gerencia", "Margenes, ventas, caja, bancos, rutas y desempeno comercial.")}
        </div>
      </article>

      <article class="future-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Indicadores</p>
            <h2>Tablero ejecutivo</h2>
          </div>
        </div>
        <div class="executive-board">
          ${metricLine("Ventas del mes", "$ 428.7M", 78)}
          ${metricLine("Margen promedio", "23.4%", 62)}
          ${metricLine("Rutas cumplidas", "91%", 91)}
          ${metricLine("Cobranza al dia", "84%", 84)}
        </div>
      </article>
    </section>
  `;
}

function quickAction(icon, title, text, target) {
  return `
    <button class="quick-action" type="button" data-open="${target}">
      <b>${icon}</b>
      <span><strong>${title}</strong><small>${text}</small></span>
    </button>
  `;
}

function futureGroup(title, items) {
  return `
    <div class="future-group">
      <h3>${title}</h3>
      ${items.map(item => `<span>${item}</span>`).join("")}
    </div>
  `;
}

function queueItem(title, detail, priority) {
  return `
    <div class="queue-item">
      <span></span>
      <p><strong>${title}</strong><small>${detail}</small></p>
      <b>${priority}</b>
    </div>
  `;
}

function roleCard(title, text) {
  return `
    <div class="role-card">
      <strong>${title}</strong>
      <p>${text}</p>
    </div>
  `;
}

function metricLine(label, value, pct) {
  return `
    <div class="metric-line">
      <label><span>${label}</span><strong>${value}</strong></label>
      <div><span style="width:${pct}%"></span></div>
    </div>
  `;
}

async function renderHome() {
  const userName = currentUser?.user_name || "Usuario";
  const localCode = currentLocal?.local_codigo || "";
  const localQuery = localCode ? `?local_codigo=${encodeURIComponent(localCode)}` : "";
  $("content").innerHTML = `<div class="home-loading"><strong>Cargando panel operativo...</strong></div>`;
  const stats = await fetchJson(`/api/dashboard${localQuery}`, {
    ventas_dia: 0, nv_dia: 0, facturas_entregar: 0,
    rutas_entrega: 0, compras_7_dias: 0, clientes_activos: 0,
    folios: {facturas:0, boletas:0, guias:0, notas_credito:0}
  });
  const local = stats.local || currentLocal || {};
  const folios = stats.folios || {};
  $("content").innerHTML = `
    <section class="home-session-bar">
      <div class="session-summary">
        <span>Sesion activa</span>
        <strong>${escapeHtml(local.codigo || local.local_codigo || localCode)} · ${escapeHtml(local.descripcion || local.local_descripcion || "Local")}</strong>
        <small>Caja ${escapeHtml(currentCaja?.caja_codigo || "-")} · ${escapeHtml(userName)} · <span data-live-datetime>${new Date().toLocaleString("es-CL")}</span></small>
      </div>
      <div class="session-actions">
        <button class="primary" type="button" data-open="facturas">Nueva factura</button>
        <button class="ghost" type="button" data-open="picking">Generar picking</button>
      </div>
    </section>

    <section class="folio-section">
      <div class="section-head compact"><div><p class="eyebrow">Control documental general</p><h2>Rangos y saldos disponibles</h2></div></div>
      <div class="folio-grid">
        ${folioCard("Facturas", folios.facturas, "Local 01: DTE final - inicial", "FV")}
        ${folioCard("Boletas", folios.boletas, "Local 02: factura - boleta", "BL")}
        ${folioCard("Guias", folios.guias, "Local 01: GE final - inicial", "GD")}
        ${folioCard("Notas de credito", folios.notas_credito, "Local 01: NC final - inicial", "NC")}
      </div>
    </section>

    <section class="dashboard-grid traffic-grid">
      ${kpiCard("Ventas del dia", stats.ventas_dia, "Documentos comerciales", "good")}
      ${kpiCard("NV del dia", stats.nv_dia, "Notas de venta generadas", "info")}
      ${kpiCard("Por entregar", stats.facturas_entregar, "Facturas y guias pendientes", stats.facturas_entregar > 20 ? "warn" : "good")}
      ${kpiCard("Rutas con entrega", stats.rutas_entrega, "Rutas activas para despacho", "info")}
      ${kpiCard("Compras 7 dias", stats.compras_7_dias, "Documentos ingresados", "neutral")}
      ${kpiCard("Clientes activos", stats.clientes_activos, "Habilitados para venta", "good")}
    </section>

    <section class="dashboard-operations">
      <article class="operations-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Semaforo operativo</p>
            <h2>Prioridades del dia</h2>
          </div>
          <span class="tag">En linea</span>
        </div>
        <div class="signal-list">
          ${signalRow("Despacho pendiente", stats.facturas_entregar, stats.facturas_entregar > 20 ? "warning" : "success", "Facturas y guias por entregar")}
          ${signalRow("Rutas programadas", stats.rutas_entrega, stats.rutas_entrega ? "success" : "neutral", "Rutas con documentos asignados")}
          ${signalRow("Notas de venta", stats.nv_dia, "info", "Generadas durante la jornada")}
          ${signalRow("Compras recientes", stats.compras_7_dias, "neutral", "Ultimos siete dias")}
        </div>
      </article>

      <article class="operations-panel quick-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Accesos rapidos</p>
            <h2>Operaciones frecuentes</h2>
          </div>
        </div>
        <div class="quick-operations">
          <button type="button" data-open="facturas"><b>FV</b><span><strong>Facturas</strong><small>Consultar ventas</small></span></button>
          <button type="button" data-open="picking"><b>PK</b><span><strong>Picking</strong><small>Preparar por ruta</small></span></button>
          <button type="button" data-open="clientes"><b>CL</b><span><strong>Clientes</strong><small>Buscar y mantener</small></span></button>
          <button type="button" data-open="productos"><b>PR</b><span><strong>Productos</strong><small>Catalogo comercial</small></span></button>
        </div>
      </article>
    </section>
    <section id="dashboardCharts" class="dashboard-charts"><article class="data-chart chart-loading"><strong>Cargando graficos...</strong></article></section>
  `;
  fetchJson(`/api/dashboard/charts${localQuery}`, null).then(charts => {
    const container = $("dashboardCharts");
    if (!container || !charts) return;
    container.innerHTML = `${dailyChart("Pedidos del ultimo mes", charts.pedidos_mes_anterior, "Pedidos por dia")}${dailyChart("Facturas del mes en curso", charts.facturas_mes_actual, "Facturas por dia")}${yearComparisonChart(charts)}`;
  });
}

function folioCard(label, value, detail, code) {
  return `<article class="folio-card"><b>${code}</b><span><small>${label}</small><strong>${Number(value || 0).toLocaleString("es-CL")}</strong><em>${detail}</em></span></article>`;
}

function kpiCard(label, value, detail, status) {
  return `
    <article class="card kpi-card ${status}">
      <i></i>
      <span>${label}</span>
      <strong>${value}</strong>
      <small>${detail}</small>
    </article>
  `;
}

function signalRow(label, value, status, detail) {
  return `<div class="signal-row"><i class="${status}"></i><span><strong>${label}</strong><small>${detail}</small></span><b>${value}</b></div>`;
}

function dailyChart(title, rows, subtitle) {
  const max = Math.max(1, ...rows.map(row => Number(row.total || 0)));
  return `<article class="data-chart"><header><div><h3>${title}</h3><p>${subtitle}</p></div></header><div class="chart-bars">
    ${rows.length ? rows.map(row => `<div title="${escapeHtml(row.fecha)}: ${row.total}"><span style="height:${Math.max(3, Number(row.total || 0) / max * 100)}%"></span><small>${String(row.fecha).slice(-2)}</small></div>`).join("") : `<p class="empty">Sin movimientos para el periodo.</p>`}
  </div></article>`;
}

function yearComparisonChart(charts) {
  const months = ["Ene","Feb","Mar","Abr","May","Jun","Jul","Ago","Sep","Oct","Nov","Dic"];
  const current = Object.fromEntries(charts.facturas_por_mes.filter(row => Number(row.ano) === Number(charts.current_year)).map(row => [Number(row.mes), Number(row.total)]));
  const previous = Object.fromEntries(charts.facturas_por_mes.filter(row => Number(row.ano) === Number(charts.previous_year)).map(row => [Number(row.mes), Number(row.total)]));
  const max = Math.max(1, ...Object.values(current), ...Object.values(previous));
  return `<article class="data-chart annual-chart"><header><div><h3>Facturas mensuales</h3><p>${charts.current_year} comparado con ${charts.previous_year}</p></div><div class="chart-legend"><span>Actual</span><span>Anterior</span></div></header><div class="month-bars">
    ${months.map((month, index) => `<div><div class="paired-bars"><i style="height:${(current[index+1]||0)/max*100}%" title="${charts.current_year}: ${current[index+1]||0}"></i><b style="height:${(previous[index+1]||0)/max*100}%" title="${charts.previous_year}: ${previous[index+1]||0}"></b></div><small>${month}</small></div>`).join("")}
  </div></article>`;
}

function bar(label, percent, value) {
  return `
    <div class="bar-row">
      <label><span>${label}</span><strong>${value}</strong></label>
      <div class="bar-track"><span style="width:${percent}%"></span></div>
    </div>
  `;
}

function activity(letter, title, subtitle, value) {
  return `
    <div>
      <b>${letter}</b>
      <p><strong>${title}</strong><span>${subtitle}</span></p>
      <strong>${value}</strong>
    </div>
  `;
}

function moduleTile(item) {
  return `
    <article class="module-card">
      <div>
        <span class="tag">${item.group}</span>
        <h3>${item.label}</h3>
        <p>${moduleDescription(item)}</p>
      </div>
      <footer>
        <small>${item.table ? "Tabla: " + item.table : "Modulo ejecutivo"}</small>
        <button class="ghost" type="button" data-open="${item.id}">Abrir</button>
      </footer>
    </article>
  `;
}

function moduleDescription(item) {
  if (item.group === "Parametros") return "Mantencion de codigos maestros para operar el sistema comercial.";
  if (item.group === "Ventas") return `Gestion documental comercial${item.doc ? " para " + item.doc : ""}.`;
  if (item.group === "Compras") return "Registro y seguimiento de compras, recepciones y proveedores.";
  if (item.group === "Reporte") return "Consulta preparada para analisis y exportacion futura.";
  return "Indicadores gerenciales para control de resultados y decisiones.";
}

function renderModule(item) {
  $("content").innerHTML = `
    <section class="chart-card">
      <div class="section-head">
        <div>
          <p class="eyebrow">${item.group}</p>
          <h2>${item.label}</h2>
          <p>${moduleDescription(item)}</p>
        </div>
        <button class="primary" type="button">Nuevo registro</button>
      </div>
      <div class="mockup-toolbar">
        <input type="search" placeholder="Buscar en ${escapeHtml(item.label)}">
        <button class="ghost" type="button">Filtrar</button>
        <button class="ghost" type="button">Exportar</button>
      </div>
      <div class="mockup-table">
        <div class="mockup-row head"><span>Codigo</span><span>Descripcion</span><span>Estado</span><span>Accion</span></div>
        ${["001", "002", "003", "004"].map((code, idx) => `
          <div class="mockup-row">
            <span>${code}</span>
            <span>${escapeHtml(item.label)} ${idx + 1}</span>
            <span><mark>Activo</mark></span>
            <span><button class="ghost small" type="button">Editar</button></span>
          </div>
        `).join("")}
      </div>
    </section>
  `;
}

async function fetchJson(url, fallback = null) {
  return fetch(url).then(r => r.ok ? r.json() : fallback).catch(() => fallback);
}

async function saveJson(url, method, body) {
  const response = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({ detail: "No fue posible guardar" }));
    throw new Error(detail.detail || "No fue posible guardar");
  }
  return response.json();
}

function yesNoField(key) {
  return /activo|cajero|cambiaprecio|maneja|aviso|agenda|domingo|restaurant|barra|menudia|credito|cheque/i.test(key);
}

function formDataObject(form) {
  return Object.fromEntries(new FormData(form).entries());
}

function fieldValue(value) {
  if (value === null || value === undefined || value === "") return "-";
  return String(value).trim();
}

function maintainerStatus(row, meta) {
  const statusKey = meta.fields.find(field => /estado|activo/i.test(field.key))?.key;
  if (!statusKey) return { label: "Vigente", inactive: false };
  const value = String(row[statusKey] ?? "A").trim().toUpperCase();
  const inactive = ["I", "N", "0", "B", "C"].includes(value);
  return { label: inactive ? "Inactivo" : "Activo", inactive };
}

async function renderProfessionalMaintainer(item) {
  $("content").innerHTML = `
    <section class="professional-maintainer">
      <header class="maintainer-heading">
        <div>
          <p class="eyebrow">Parametros / Base real</p>
          <h2>${escapeHtml(item.label)}</h2>
          <p>Administracion centralizada de ${escapeHtml(item.label.toLowerCase())} del sistema.</p>
        </div>
        <button class="primary" type="button" data-new-master>+ Nuevo registro</button>
      </header>
      <div class="maintainer-filterbar">
        <label class="search-field">Buscar
          <input id="masterSearch" type="search" placeholder="Codigo, nombre o detalle">
        </label>
        <button id="masterSearchButton" class="primary" type="button">Buscar</button>
        <button id="masterClearButton" class="ghost" type="button">Limpiar</button>
        <button class="ghost export-button" type="button">CSV</button>
      </div>
      <div class="professional-table-wrap">
        <table class="professional-table">
          <thead id="masterHead"></thead>
          <tbody id="masterRows"><tr><td class="empty">Cargando datos reales...</td></tr></tbody>
        </table>
      </div>
      <div class="table-footer"><span id="masterCount">Consultando...</span><span>Actualizado desde ${escapeHtml(item.table || "base real")}</span></div>
    </section>
    <div id="masterDrawer" class="editor-overlay" aria-hidden="true"></div>
  `;

  const load = () => loadProfessionalMaintainer(item);
  $("masterSearchButton").addEventListener("click", load);
  $("masterClearButton").addEventListener("click", () => { $("masterSearch").value = ""; load(); });
  $("masterSearch").addEventListener("keydown", event => { if (event.key === "Enter") load(); });
  $("masterRows").addEventListener("click", event => {
    const more = event.target.closest("[data-more-index]");
    if (more) {
      document.querySelectorAll(".row-actions-menu.open").forEach(menu => menu.classList.remove("open"));
      more.parentElement.querySelector(".row-actions-menu")?.classList.toggle("open");
      return;
    }
    const action = event.target.closest("[data-master-action]");
    if (action && window.currentMasterData) {
      const index = Number(action.dataset.masterIndex);
      if (action.dataset.masterAction === "edit") openMasterDrawer(window.currentMasterData, index);
      if (action.dataset.masterAction === "duplicate") openMasterDrawer(window.currentMasterData, index, true);
      if (action.dataset.masterAction === "copy") navigator.clipboard?.writeText(String(window.currentMasterData.rows[index][window.currentMasterData.meta.key] ?? ""));
      return;
    }
    const button = event.target.closest("[data-master-index]");
    if (!button || !window.currentMasterData) return;
    openMasterDrawer(window.currentMasterData, Number(button.dataset.masterIndex));
  });
  document.querySelector("[data-new-master]").addEventListener("click", () => openMasterDrawer(window.currentMasterData, -1));
  await load();
}

async function loadProfessionalMaintainer(item) {
  const query = new URLSearchParams({ limit: "200" });
  if ($("masterSearch")?.value) query.set("q", $("masterSearch").value);
  const data = await fetchJson(`/api/maestros/${encodeURIComponent(item.id)}?${query}`, null);
  if (!data) {
    $("masterRows").innerHTML = `<tr><td class="empty">No fue posible conectar con la tabla ${escapeHtml(item.table || "")}.</td></tr>`;
    return;
  }
  window.currentMasterData = data;
  const visibleFields = data.meta.fields.filter(field => !/estado|activo/i.test(field.key)).slice(0, 6);
  $("masterHead").innerHTML = `<tr>${visibleFields.map(field => `<th>${escapeHtml(field.label)}</th>`).join("")}<th>Estado</th><th class="actions-col">Acciones</th></tr>`;
  $("masterCount").textContent = `${data.rows.length} registros visibles`;
  $("masterRows").innerHTML = data.rows.length ? data.rows.map((row, index) => {
    const status = maintainerStatus(row, data.meta);
    return `<tr>
      ${visibleFields.map((field, fieldIndex) => `<td class="${fieldIndex === 0 ? "key-cell" : ""}">${escapeHtml(fieldValue(row[field.key]))}</td>`).join("")}
      <td><span class="status-badge ${status.inactive ? "inactive" : ""}">${status.label}</span></td>
      <td class="actions-col row-actions"><button class="row-edit" data-master-index="${index}" type="button">Editar</button><button class="row-more" data-more-index="${index}" type="button" title="Mas opciones">...</button>
        <div class="row-actions-menu"><button data-master-action="edit" data-master-index="${index}">Editar</button><button data-master-action="duplicate" data-master-index="${index}">Duplicar</button><button data-master-action="copy" data-master-index="${index}">Copiar codigo</button></div>
      </td>
    </tr>`;
  }).join("") : `<tr><td colspan="${visibleFields.length + 2}" class="empty">No hay registros para la busqueda seleccionada.</td></tr>`;
}

async function openMasterDrawer(data, index, duplicate = false) {
  if (!data) return;
  const isNew = index < 0 || duplicate;
  const row = index < 0 ? {} : data.rows[index];
  const vendedores = data.meta.id === "usuarios" ? await fetchJson("/api/vendedores", []) : [];
  const overlay = $("masterDrawer");
  overlay.innerHTML = `
    <aside class="editor-drawer">
      <header><div><p class="eyebrow">${isNew ? "Nuevo registro" : "Edicion"}</p><h3>${escapeHtml(data.meta.title)}</h3></div><button class="drawer-close" type="button" aria-label="Cerrar">x</button></header>
      <div class="drawer-identity"><span>${isNew ? "NUEVO" : escapeHtml(fieldValue(row[data.meta.key]))}</span><strong>${isNew ? "Crear " + escapeHtml(data.meta.title.toLowerCase()) : escapeHtml(fieldValue(row[data.meta.name]))}</strong></div>
      <form class="drawer-form" id="masterEditForm">
        ${data.meta.fields.map((field, fieldIndex) => {
          const value = duplicate && fieldIndex === 0 ? "" : (row[field.key] ?? "");
          if (field.key === "vendedor_codigo") return `<label>${escapeHtml(field.label)}<select name="${field.key}">${optionsHtml(vendedores, value, "Sin vendedor")}</select></label>`;
          if (field.key === "vendedor_estado") return `<label>${escapeHtml(field.label)}<select name="${field.key}">${optionsHtml([{value:"A",label:"ACTIVO"},{value:"B",label:"BAJA"}], value, "Seleccione")}</select></label>`;
          if (yesNoField(field.key)) return `<label>${escapeHtml(field.label)}<select name="${field.key}">${optionsHtml([{value:"S",label:"SI"},{value:"N",label:"NO"}], value, "Seleccione")}</select></label>`;
          return `<label>${escapeHtml(field.label)}<input name="${field.key}" value="${escapeHtml(value)}" ${!isNew && fieldIndex === 0 ? "readonly" : ""}></label>`;
        }).join("")}
      </form>
      <footer><span class="save-status"></span><button class="ghost drawer-cancel" type="button">Cancelar</button><button class="primary master-save" type="button">Guardar cambios</button></footer>
    </aside>`;
  overlay.classList.add("open");
  overlay.setAttribute("aria-hidden", "false");
  const close = () => { overlay.classList.remove("open"); overlay.setAttribute("aria-hidden", "true"); };
  overlay.querySelector(".drawer-close").addEventListener("click", close);
  overlay.querySelector(".drawer-cancel").addEventListener("click", close);
  overlay.querySelector(".master-save").addEventListener("click", async () => {
    const status = overlay.querySelector(".save-status");
    try {
      status.textContent = "Guardando...";
      await saveJson(`/api/maestros/${encodeURIComponent(data.meta.id)}`, isNew ? "POST" : "PUT", {
        data: formDataObject(overlay.querySelector("#masterEditForm")), original: row
      });
      status.textContent = "Guardado";
      await loadProfessionalMaintainer(moduleLookup.get(data.meta.id));
      close();
    } catch (error) { status.textContent = error.message; }
  });
  overlay.addEventListener("click", event => { if (event.target === overlay) close(); }, { once: true });
}

async function loadClienteCatalogos() {
  if (clienteCatalogos) return clienteCatalogos;
  clienteCatalogos = await fetchJson("/api/catalogos/clientes", {
    rutas: [],
    ciudades: [],
    comunas: [],
    vendedores: [],
    listas: [],
    condiciones: [],
    estados: []
  });
  return clienteCatalogos;
}

function optionsHtml(rows, value = "", placeholder = "(Ninguno)") {
  const current = String(value ?? "");
  return [
    `<option value="">${escapeHtml(placeholder)}</option>`,
    ...(rows || []).map(row => {
      const optionValue = String(row.value ?? row.ruta_id ?? row.vendedor_codigo ?? "");
      const label = String(row.label ?? row.ruta_nombre ?? row.vendedor_nombre ?? optionValue);
      return `<option value="${escapeHtml(optionValue)}" ${optionValue === current ? "selected" : ""}>${escapeHtml(label)}</option>`;
    })
  ].join("");
}

function clienteEstadoLabel(value) {
  const clean = String(value || "A").toUpperCase();
  return clean === "I" || clean === "N" ? "INACTIVO" : "ACTIVO";
}

async function renderClientes() {
  const catalogos = await loadClienteCatalogos();
  $("content").innerHTML = `
    <section class="professional-maintainer">
      <header class="maintainer-heading">
        <div><p class="eyebrow">Parametros / Base real</p><h2>Clientes</h2><p>Gestion comercial, condiciones, rutas y datos de contacto.</p></div>
        <button class="primary" type="button">+ Nuevo cliente</button>
      </header>
      <div class="maintainer-filterbar clients-filterbar">
        <label>RUT<input id="clienteRutFilter" type="search" autofocus></label>
        <label>NOMBRE<input id="clienteNombreFilter" type="search"></label>
        <label>RUTA<select id="clienteRutaFilter">${optionsHtml(catalogos.rutas)}</select></label>
        <button id="clienteBuscar" class="primary" type="button">Buscar</button>
        <button class="ghost" type="button">CSV</button>
      </div>
      <div class="professional-table-wrap">
        <table class="professional-table">
          <thead>
            <tr>
              <th>Rut</th>
              <th>Nombre</th>
              <th>Direccion</th>
              <th>Ciudad</th>
              <th>Condicion</th>
              <th>Mail SII</th>
              <th>Estado</th>
              <th class="actions-col">Acciones</th>
            </tr>
          </thead>
          <tbody id="clientesRows">
            <tr><td colspan="8" class="empty">Cargando clientes reales...</td></tr>
          </tbody>
        </table>
      </div>
      <div class="table-footer"><span id="clientesCount">Consultando...</span><span>Informacion comercial actualizada</span></div>
    </section>
    <div id="clienteEditor" class="editor-overlay" aria-hidden="true"></div>
  `;

  $("clienteBuscar").addEventListener("click", loadClientes);
  $("clienteRutFilter").addEventListener("keydown", event => { if (event.key === "Enter") loadClientes(); });
  $("clienteNombreFilter").addEventListener("keydown", event => { if (event.key === "Enter") loadClientes(); });
  $("clientesRows").addEventListener("click", async (event) => {
    const button = event.target.closest("[data-rut]");
    if (!button) return;
    const detail = await fetchJson(`/api/clientes/${encodeURIComponent(button.dataset.rut)}`, null);
    if (detail) renderClienteEditor(detail);
  });
  await loadClientes();
}

async function loadClientes() {
  const query = new URLSearchParams();
  const rut = $("clienteRutFilter")?.value || "";
  const nombre = $("clienteNombreFilter")?.value || "";
  const ruta = $("clienteRutaFilter")?.value || "";
  if (rut) query.set("rut", rut);
  if (nombre) query.set("nombre", nombre);
  if (ruta) query.set("ruta_id", ruta);
  query.set("limit", "120");
  const rows = await fetchJson(`/api/clientes?${query.toString()}`, []);
  const tbody = $("clientesRows");
  if ($("clientesCount")) $("clientesCount").textContent = `${rows.length} clientes visibles`;
  if (!rows.length) {
    tbody.innerHTML = `<tr><td colspan="8" class="empty">No hay clientes para los filtros seleccionados.</td></tr>`;
    return;
  }
  tbody.innerHTML = rows.map(row => `
    <tr>
      <td><strong>${escapeHtml(row.cliente_rut)}</strong></td>
      <td>${escapeHtml(row.cliente_nombre || "")}</td>
      <td><strong>${escapeHtml(row.cliente_direccion || "")}</strong></td>
      <td>${escapeHtml(row.Ciudad_codigo || "")}</td>
      <td>${escapeHtml(row.condicion_nombre || row.cliente_condiccion || "")}</td>
      <td>${escapeHtml(row.cliente_mail || "")}</td>
      <td><span class="state-pill">${clienteEstadoLabel(row.cliente_estado)}</span></td>
      <td class="actions-col"><button class="row-edit" type="button" data-rut="${escapeHtml(row.cliente_rut)}">Editar</button><button class="row-more" type="button" data-rut="${escapeHtml(row.cliente_rut)}" title="Abrir ficha">...</button></td>
    </tr>
  `).join("");
}

async function renderClienteEditor(cliente) {
  const catalogos = await loadClienteCatalogos();
  const comunas = (catalogos.comunas || []).filter(c => !cliente.Ciudad_codigo || c.ciudad === cliente.Ciudad_codigo);
  const overlay = $("clienteEditor");
  overlay.innerHTML = `
    <aside class="editor-drawer client-drawer">
      <header><div><p class="eyebrow">Ficha comercial</p><h3>Editar cliente</h3></div><button class="drawer-close" type="button">x</button></header>
      <div class="drawer-identity"><span>${escapeHtml(cliente.cliente_rut || "")}</span><strong>${escapeHtml(cliente.cliente_nombre || "Sin nombre")}</strong></div>
      <form class="client-form drawer-scroll">
      <div class="form-grid two">
        <label>Rut <input value="${escapeHtml(cliente.cliente_rut || "")}" readonly></label>
        <label class="required">Nombre <input name="cliente_nombre" value="${escapeHtml(cliente.cliente_nombre || "")}"></label>
        <label>Direccion <input name="cliente_direccion" class="hot" value="${escapeHtml(cliente.cliente_direccion || "")}"></label>
        <label>Giro <input name="cliente_giro" class="hot" value="${escapeHtml(cliente.cliente_giro || "")}"></label>
        <label>Telefono <input name="cliente_telefono" value="${escapeHtml(cliente.cliente_telefono || "")}"></label>
        <label>Celular <input name="cliente_celular" class="hot" value="${escapeHtml(cliente.cliente_celular || "")}"></label>
        <label>Mail <input name="cliente_mail" value="${escapeHtml(cliente.cliente_mail || "")}"></label>
        <label>Intercambio <input name="cliente_intercambio" value="${escapeHtml(cliente.cliente_intercambio || "")}"></label>
        <label>Vendedor <select name="cliente_vendedor">${optionsHtml(catalogos.vendedores, cliente.cliente_vendedor, "Todos..")}</select></label>
      </div>

      <div class="section-strip">INFORMACION BANCARIA</div>
      <div class="bank-space"></div>

      <div class="form-grid client-bottom">
        <label>Ciudad <select name="Ciudad_codigo" class="hot">${optionsHtml(catalogos.ciudades, cliente.Ciudad_codigo, "Ciudad")}</select></label>
        <label>Comuna <select name="Comuna" class="hot">${optionsHtml(comunas, cliente.Comuna, "Comuna")}</select></label>
        <label>Descuento (%) <input name="cliente_descuento" class="money-input" value="${cliente.cliente_descuento ?? 0}"></label>
        <label>Ruta <select name="ruta_id" class="hot">${optionsHtml(catalogos.rutas, String(cliente.ruta_id || ""), "Ruta")}</select></label>
        <label>Condicion <select name="cliente_condiccion" class="hot">${optionsHtml(catalogos.condiciones || [], cliente.cliente_condiccion, "Condicion")}</select></label>
        <label>Estado <select name="cliente_estado">${optionsHtml(catalogos.estados, cliente.cliente_estado || "A", "Estado")}</select></label>
        <label>Lista <select name="lista_codigo" class="hot">${optionsHtml(catalogos.listas, cliente.lista_codigo, "Lista")}</select></label>
        <label>Geo <input name="cliente_geo" value="${escapeHtml(cliente.cliente_geo || "")}"></label>
        <label>Ult. Desbloqueo <input value="" readonly></label>
      </div>
      <div class="form-actions">
        <span class="save-status"></span><button class="primary client-save" type="button">Guardar cambios</button>
        <button class="ghost drawer-cancel" type="button">Cancelar</button>
      </div>
      </form>
    </aside>
  `;
  overlay.classList.add("open");
  overlay.setAttribute("aria-hidden", "false");
  const close = () => { overlay.classList.remove("open"); overlay.setAttribute("aria-hidden", "true"); };
  overlay.querySelector(".drawer-close").addEventListener("click", close);
  overlay.querySelector(".drawer-cancel").addEventListener("click", close);
  overlay.querySelector(".client-save").addEventListener("click", async () => {
    const status = overlay.querySelector(".save-status");
    try {
      status.textContent = "Guardando...";
      await saveJson(`/api/clientes/${encodeURIComponent(cliente.cliente_rut)}`, "PUT", { data: formDataObject(overlay.querySelector("form")) });
      status.textContent = "Guardado";
      await loadClientes();
      close();
    } catch (error) { status.textContent = error.message; }
  });
}

async function renderProductos() {
  productoCatalogos = productoCatalogos || await fetchJson("/api/catalogos/productos", {familias:[],subfamilias:[],proveedores:[],unidades:[],listas:[],impuestos:[],si_no:[]});
  $("content").innerHTML = `
    <section class="professional-maintainer products-maintainer">
      <header class="maintainer-heading">
        <div><p class="eyebrow">Parametros / Base real</p><h2>Productos</h2></div>
        <button id="productoNuevo" class="primary" type="button">+ Nuevo producto</button>
      </header>
      <div class="maintainer-filterbar products-filterbar">
        <label>CODIGO<input id="productoCodigoFilter" type="search"></label>
        <label>DESCRIPCION<input id="productoDescFilter" type="search"></label>
        <label>LISTA<select id="productoListaFilter">${optionsHtml(productoCatalogos.listas, "01", "Lista")}</select></label>
        <label>FAMILIA<select id="productoFamiliaFilter">${optionsHtml(productoCatalogos.familias, "", "Todos")}</select></label>
        <label>PROVEEDOR<select id="productoProveedorFilter">${optionsHtml(productoCatalogos.proveedores, "", "Todos")}</select></label>
        <label>BARRA<input id="productoBarraFilter" type="search"></label>
        <label class="inline-check"><input id="productoTodosFilter" type="checkbox"> INCLUIR TODOS</label>
        <button id="productoBuscar" class="primary" type="button">Buscar</button>
        <button class="ghost" type="button">CSV</button>
      </div>
      <div class="professional-table-wrap">
        <table class="professional-table">
          <thead>
            <tr>
              <th>Codigo</th>
              <th>Descripcion</th>
              <th>Familia</th>
              <th>Margen base</th>
              <th>Margen lista</th>
              <th>Costo lista</th>
              <th>Neto lista</th>
              <th>Venta lista</th>
              <th>Estado</th>
              <th class="actions-col">Acciones</th>
            </tr>
          </thead>
          <tbody id="productosRows">
            <tr><td colspan="10" class="empty">Cargando productos reales...</td></tr>
          </tbody>
        </table>
      </div>
      <div class="table-footer"><span id="productosCount">Consultando...</span><span>Catalogo comercial actualizado</span></div>
    </section>
    <div id="productoEditor" class="editor-overlay" aria-hidden="true"></div>
  `;
  $("productoBuscar").addEventListener("click", loadProductos);
  $("productoNuevo").addEventListener("click", () => openProductDrawer({producto_estado:"2", producto_pack:"N", producto_manejaiva:"S"}, true));
  for (const id of ["productoCodigoFilter", "productoDescFilter", "productoBarraFilter"]) {
    $(id).addEventListener("keydown", event => { if (event.key === "Enter") loadProductos(); });
  }
  $("productosRows").addEventListener("click", async event => {
    const button = event.target.closest("[data-product-index]");
    if (button && window.currentProductos) {
      const row = window.currentProductos[Number(button.dataset.productIndex)];
      const detail = await fetchJson(`/api/productos/${encodeURIComponent(row.producto_codigo)}?lista=${encodeURIComponent($("productoListaFilter").value || "01")}`, row);
      openProductDrawer(detail);
    }
  });
  await loadProductos();
}

async function loadProductos() {
  const query = new URLSearchParams();
  if ($("productoCodigoFilter")?.value) query.set("codigo", $("productoCodigoFilter").value);
  if ($("productoDescFilter")?.value) query.set("descripcion", $("productoDescFilter").value);
  if ($("productoFamiliaFilter")?.value) query.set("familia", $("productoFamiliaFilter").value);
  if ($("productoProveedorFilter")?.value) query.set("proveedor", $("productoProveedorFilter").value);
  if ($("productoBarraFilter")?.value) query.set("barra", $("productoBarraFilter").value);
  query.set("lista", $("productoListaFilter")?.value || "01");
  query.set("incluir_todos", $("productoTodosFilter")?.checked ? "true" : "false");
  query.set("limit", "150");
  const rows = await fetchJson(`/api/productos?${query.toString()}`, []);
  window.currentProductos = rows;
  const tbody = $("productosRows");
  if ($("productosCount")) $("productosCount").textContent = `${rows.length} productos visibles`;
  if (!rows.length) {
    tbody.innerHTML = `<tr><td colspan="10" class="empty">No hay productos para los filtros seleccionados.</td></tr>`;
    return;
  }
  tbody.innerHTML = rows.map((row, index) => `
    <tr>
      <td><strong>${escapeHtml(row.producto_codigo || "")}</strong></td>
      <td>${escapeHtml(row.producto_descripcion || "")}</td>
      <td>${escapeHtml(row.familia_descripcion || row.familia_codigo || "")}</td>
      <td>${clNumber(row.producto_margenvta, 2)}</td>
      <td>${clNumber(row.lista_margen, 2)}</td>
      <td>${clNumber(row.lista_costo, 2)}</td>
      <td>${clNumber(row.lista_neto, 0)}</td>
      <td>${clNumber(row.lista_venta, 0)}</td>
      <td><span class="state-pill">${clienteEstadoLabel(row.producto_estado)}</span></td>
      <td class="actions-col"><button class="row-edit" type="button" data-product-index="${index}">Editar</button><button class="row-more" type="button" data-product-index="${index}" title="Abrir ficha">...</button></td>
    </tr>
  `).join("");
}

function openProductDrawer(producto, isNew = false) {
  const overlay = $("productoEditor");
  if (!overlay || !producto) return;
  const subfamilias = (productoCatalogos.subfamilias || []).filter(row => !producto.familia_codigo || row.parent === producto.familia_codigo);
  const lista = $("productoListaFilter")?.value || "01";
  overlay.innerHTML = `<aside class="editor-drawer product-drawer"><header><div><p class="eyebrow">Ficha de producto</p><h3>${isNew ? "Nuevo producto" : "Editar producto"}</h3></div><button class="drawer-close" type="button">x</button></header>
    <div class="drawer-identity"><span>${escapeHtml(producto.producto_codigo || "NUEVO")}</span><strong>${escapeHtml(producto.producto_descripcion || "Producto sin descripcion")}</strong></div>
    <form class="product-form">
      <input type="hidden" name="lista_codigo" value="${escapeHtml(lista)}">
      <fieldset class="product-info"><legend>Informacion del producto</legend>
        <label>Codigo<input name="producto_codigo" value="${escapeHtml(producto.producto_codigo || "")}" ${isNew ? "" : "readonly"}></label>
        <label>Descripcion<input name="producto_descripcion" value="${escapeHtml(producto.producto_descripcion || "")}"></label>
        <label>Familia<select name="familia_codigo">${optionsHtml(productoCatalogos.familias, producto.familia_codigo, "Familia")}</select></label>
        <label>Sub-familia<select name="subfamilia_codigo">${optionsHtml(subfamilias, producto.subfamilia_codigo, "(Ninguno)")}</select></label>
        <label>Ubicacion<input name="producto_ubicacion" value="${escapeHtml(producto.producto_ubicacion || "")}"></label>
        <label>Proveedor<select name="producto_proveedor">${optionsHtml(productoCatalogos.proveedores, producto.producto_proveedor, "Proveedor")}</select></label>
        <label>Barra unidad<input name="producto_barra" value="${escapeHtml(producto.producto_barra || "")}"></label>
        <label>Estado<select name="producto_estado">${optionsHtml([{value:"2",label:"NUEVO"},{value:"A",label:"ACTIVO"},{value:"0",label:"ACTIVO"},{value:"I",label:"INACTIVO"},{value:"N",label:"INACTIVO"}], producto.producto_estado, "Estado")}</select></label>
      </fieldset>
      <fieldset><legend>Impuestos</legend>
        <label>Impuesto<select name="impuesto_codigo">${optionsHtml(productoCatalogos.impuestos || [], producto.impuesto_codigo, "(Ninguno)")}</select></label>
        <label>Valor impuesto<input name="impuesto_valor" value="0" readonly></label>
        <label>Valor ILA<input name="producto_ila" value="${producto.producto_ila ?? 0}" readonly></label>
        <label>IVA<select name="producto_manejaiva">${optionsHtml(productoCatalogos.si_no, producto.producto_manejaiva, "IVA")}</select></label>
      </fieldset>
      <fieldset><legend>Costeo del producto</legend>
        <label>Costo anterior<input name="producto_costoant" value="${producto.producto_costoant ?? 0}"></label>
        <label>Costo especial<input name="producto_costoof" value="${producto.producto_costoof ?? 0}"></label>
        <label>Costo<input name="producto_costo" value="${producto.producto_costo ?? 0}"></label>
        <label>Costo lista<input name="Lista_costo" value="${producto.Lista_costo ?? producto.producto_costo ?? 0}"></label>
      </fieldset>
      <fieldset><legend>Margen y precios</legend>
        <label>Margen venta<input name="producto_margenvta" value="${producto.producto_margenvta ?? 0}"></label>
        <label>Neto venta<input name="producto_netoventa" value="${producto.producto_netoventa ?? 0}" readonly></label>
        <label>Margen lista<input name="lista_margen" value="${producto.lista_margen ?? 0}"></label>
        <label>Valor neto<input name="lista_neto" value="${producto.lista_neto ?? producto.producto_neto ?? 0}" readonly></label>
        <label>Valor IVA<input name="lista_iva" value="${producto.lista_iva ?? producto.producto_iva ?? 0}" readonly></label>
        <label>Precio unitario<input name="lista_venta" value="${producto.lista_venta ?? producto.producto_venta ?? 0}" readonly></label>
        <label>Descuento maximo<input name="producto_descuento" value="${producto.producto_descuento ?? 0}"></label>
        <label>Precio oferta<input name="producto_oferta" value="${producto.producto_oferta ?? 0}"></label>
        <label>Oferta neta<input name="producto_ofertaneto" value="${producto.producto_ofertaneto ?? 0}" readonly></label>
      </fieldset>
      <fieldset class="inventory-fieldset"><legend>Inventario</legend>
        <label>Unidad medida<select name="unidad_codigo">${optionsHtml(productoCatalogos.unidades, producto.unidad_codigo, "Unidad")}</select></label>
        <label>Unidad x envase<input name="producto_unidadenvase" value="${producto.producto_unidadenvase ?? 0}"></label>
        <label>Peso<input name="producto_peso" value="${producto.producto_peso ?? 0}"></label>
        <label>Gramaje<input name="producto_gramaje" value="${producto.producto_gramaje ?? 0}"></label>
        <label>Stock<input name="producto_stock" value="${producto.producto_stock ?? 0}" readonly></label>
        <label>Costo sin flete<input name="producto_costosinflete" value="${producto.producto_costosinflete ?? 0}"></label>
        <label>Valorizado costo sin flete<input name="producto_valorizadocostosin" value="${producto.producto_valorizadocostosin ?? 0}" readonly></label>
        <label>Stock envase<input name="producto_stockenvase" value="${producto.producto_stockenvase ?? 0}" readonly></label>
        <label>Stock minimo<input name="producto_stockmin" value="${producto.producto_stockmin ?? 0}"></label>
        <label>Descuenta stock<select name="producto_descuentastock">${optionsHtml(productoCatalogos.si_no, producto.producto_descuentastock, "Seleccione")}</select></label>
        <label>Pack<select name="producto_pack">${optionsHtml(productoCatalogos.si_no, producto.producto_pack || "N", "Seleccione")}</select></label>
        <label>Serial<input name="producto_serial" value="${producto.producto_serial ?? 0}"></label>
      </fieldset>
      <section class="product-relations">
        <h4>Datos relacionados</h4>
        <div class="relation-grid">
          ${productRelationTable("Stock por bodega", producto.relaciones?.bodegas, [["bodega_codigo","Bodega"],["bodega_descripcion","Nombre"],["producto_stockbodega","Stock"],["producto_reservado","Reservado"]])}
          ${productRelationTable("Comisiones", producto.relaciones?.comisiones, [["empleado_codigo","Empleado"],["empleado_nombre","Nombre"],["producto_comision","Comision %"]])}
          ${productRelationTable("Historial de compras", producto.relaciones?.compras, [["producto_comprafecha","Fecha"],["proveedor_nombre","Proveedor"],["producto_compracant","Cantidad"],["producto_compraprecio","Precio"],["producto_numerodoc","Documento"]])}
          ${productRelationTable("Cierres mensuales", producto.relaciones?.cierres, [["cierre_mes","Mes"],["cierre_ano","Ano"],["cierre_cantidad","Cantidad"],["cierre_venta","Venta"],["cierre_costoneto","Costo neto"]])}
          ${productRelationTable("Componentes del pack", producto.relaciones?.componentes, [["producto_codigo1","Codigo"],["producto_descripcion","Producto"],["producto_cantidad","Cantidad"],["producto_packprecio","Precio"]])}
          ${productRelationTable("Codigos EAN", producto.relaciones?.ean, [["producto_ean","EAN"]])}
        </div>
      </section>
    </form>
    <footer><span class="save-status"></span><button class="ghost drawer-cancel" type="button">Cancelar</button><button class="primary product-save" type="button">Guardar cambios</button></footer></aside>`;
  overlay.classList.add("open");
  overlay.setAttribute("aria-hidden", "false");
  const close = () => { overlay.classList.remove("open"); overlay.setAttribute("aria-hidden", "true"); };
  overlay.querySelector(".drawer-close").addEventListener("click", close);
  overlay.querySelector(".drawer-cancel").addEventListener("click", close);
  const form = overlay.querySelector("form");
  const recalculate = () => calculateProductForm(form);
  form.addEventListener("input", recalculate);
  form.addEventListener("change", recalculate);
  recalculate();
  overlay.querySelector(".product-save").addEventListener("click", async () => {
    const status = overlay.querySelector(".save-status");
    try {
      status.textContent = "Guardando...";
      const values = formDataObject(form);
      const endpoint = isNew ? "/api/productos" : `/api/productos/${encodeURIComponent(producto.producto_codigo)}`;
      await saveJson(endpoint, isNew ? "POST" : "PUT", {data: values});
      status.textContent = "Guardado";
      await loadProductos(); close();
    } catch (error) { status.textContent = error.message; }
  });
}

function productRelationTable(title, rows = [], columns = []) {
  return `<article class="relation-card"><h5>${escapeHtml(title)}</h5>${rows.length ? `<div><table><thead><tr>${columns.map(([, label]) => `<th>${escapeHtml(label)}</th>`).join("")}</tr></thead><tbody>${rows.map(row => `<tr>${columns.map(([key]) => `<td>${escapeHtml(fieldValue(row[key]))}</td>`).join("")}</tr>`).join("")}</tbody></table></div>` : `<p>Sin registros relacionados.</p>`}</article>`;
}

function calculateProductForm(form) {
  const number = name => Number(String(form.elements[name]?.value || 0).replace(",", ".")) || 0;
  const set = (name, value) => { if (form.elements[name]) form.elements[name].value = Math.round(value); };
  const taxCode = form.elements.impuesto_codigo?.value || "";
  const tax = Number((productoCatalogos.impuestos || []).find(row => row.value === taxCode)?.rate || 0);
  const managesVat = form.elements.producto_manejaiva?.value === "S";
  const baseNet = Math.round(number("producto_costo") * (1 + number("producto_margenvta") / 100));
  const vat = managesVat ? Math.round(baseNet * .19) : 0;
  const ila = Math.round(baseNet * tax / 100);
  const sale = Math.round(baseNet + vat + ila);
  const net = managesVat ? baseNet : sale;
  const offerNet = managesVat ? Math.round(number("producto_oferta") / (1 + (tax + 19) / 100)) : sale;
  const stockValuation = number("producto_stock") * number("producto_costosinflete");
  const stockPackage = number("producto_unidadenvase") > 0 ? number("producto_unidadenvase") * number("producto_gramaje") : 0;
  if (form.elements.impuesto_valor) form.elements.impuesto_valor.value = tax.toFixed(2);
  set("producto_netoventa", baseNet);
  set("producto_ila", ila); set("lista_neto", net); set("lista_iva", vat);
  set("lista_venta", sale); set("producto_ofertaneto", offerNet);
  if (form.elements.producto_valorizadocostosin) form.elements.producto_valorizadocostosin.value = stockValuation.toFixed(3);
  if (form.elements.producto_stockenvase) form.elements.producto_stockenvase.value = stockPackage.toFixed(3);
}

async function renderFacturas() {
  const catalogs=await fetchJson('/api/facturas/catalogos',{vendedores:[],rutas:[],repartidores:[]});
  const today=new Date().toISOString().slice(0,10);
  $("content").innerHTML=`<section class="invoice-maintainer">
    <header class="maintainer-heading"><div><p class="eyebrow">Ventas / Documentos FE-FA</p><h2>Mantenedor de facturas</h2><p>Emision, seguimiento SII, reparto, pagos y procesos masivos.</p></div></header>
    <div class="invoice-actions">
      <button data-invoice-action="new" class="primary">Nueva</button><button data-invoice-action="bulk-create">Gen. masiva</button><button data-invoice-action="bulk-print">Imp. masiva</button><button data-invoice-action="bulk-copy">Copia masiva</button><button data-invoice-action="copy-guide">Copia a guia</button><button data-invoice-action="date">Cambio fecha</button><button data-invoice-action="delivery">Asignar reparto</button><button data-invoice-action="picking">Picking</button><button data-invoice-action="all">Marcar todo</button><button data-invoice-action="send">Enviar folios</button>
    </div>
    <div class="invoice-filters">
      <label>Folio<input id="invoiceFolio" inputmode="numeric" placeholder="Folio o numero"></label><label>Desde<input id="invoiceFrom" type="date" value="${today}"></label><label>Hasta<input id="invoiceTo" type="date" value="${today}"></label><label>Local<select id="invoiceLocal"><option value="">Todos</option><option value="01" ${currentLocal.local_codigo==='01'?'selected':''}>01 · Bodega</option><option value="02" ${currentLocal.local_codigo==='02'?'selected':''}>02 · Panaderia</option></select></label><label>Vendedor<select id="invoiceSeller">${optionsHtml(catalogs.vendedores,'','Todos')}</select></label><label>Ruta<select id="invoiceRoute">${optionsHtml(catalogs.rutas,'','Todas')}</select></label><label>Reparto<select id="invoiceDelivery">${optionsHtml(catalogs.repartidores,'','Todos')}</select></label><label>Estado SII<select id="invoiceSii"><option value="">Todos</option><option value="DOK">DOK</option><option value="RCH">Rechazado</option></select></label><button id="invoiceSearch" class="primary">Buscar</button><button id="invoiceClear" class="ghost">Limpiar</button>
    </div>
    <div class="invoice-totals"><article><span>Documentos</span><strong id="invoiceCount">0</strong></article><article><span>Total facturado</span><strong id="invoiceTotal">$0</strong></article><article><span>Pagado</span><strong id="invoicePaid">$0</strong></article><article><span>Saldo</span><strong id="invoiceBalance">$0</strong></article><p id="invoiceMessage"></p></div>
    <div class="professional-table-wrap invoice-table-wrap"><table class="professional-table invoice-table"><thead><tr><th><input id="invoiceSelectAll" type="checkbox"></th><th>Acciones</th><th>Envio</th><th>Estado</th><th>Guia</th><th>#</th><th>Folio</th><th>Fecha</th><th>RUT</th><th>Cliente</th><th>Ruta</th><th>Total</th><th>Pagado</th><th>Saldo</th><th>Vendedor</th><th>Reparto</th></tr></thead><tbody id="invoiceRows"><tr><td colspan="16" class="empty">Cargando facturas...</td></tr></tbody></table></div>
  </section><div id="invoiceDetail" class="editor-overlay" aria-hidden="true"></div>`;
  $("invoiceSearch").onclick=loadFacturas;$("invoiceClear").onclick=()=>{$("invoiceFolio").value='';$("invoiceSeller").value='';$("invoiceRoute").value='';$("invoiceDelivery").value='';$("invoiceSii").value='';loadFacturas();};
  $("invoiceSelectAll").onchange=()=>document.querySelectorAll('[data-invoice-select]').forEach(x=>x.checked=$("invoiceSelectAll").checked);
  $("invoiceRows").onclick=event=>{const detail=event.target.closest('[data-invoice-detail]');if(detail)openFacturaDetail(detail.dataset.local,detail.dataset.type,detail.dataset.invoiceDetail);};
  document.querySelector('.invoice-actions').onclick=event=>{const button=event.target.closest('[data-invoice-action]');if(button)invoiceAction(button.dataset.invoiceAction,catalogs);};
  await loadFacturas();
}

function invoiceQuery(){const q=new URLSearchParams({fecha_desde:$("invoiceFrom").value,fecha_hasta:$("invoiceTo").value,limit:'300'});for(const [id,key] of [['invoiceFolio','folio'],['invoiceLocal','local_codigo'],['invoiceSeller','vendedor_codigo'],['invoiceRoute','ruta_id'],['invoiceDelivery','repartidor'],['invoiceSii','estado_sii']])if($(id).value)q.set(key,$(id).value);return q;}
async function loadFacturas(){const data=await fetchJson(`/api/facturas?${invoiceQuery()}`,{rows:[],totals:{}}),t=data.totals||{};$("invoiceCount").textContent=Number(t.documentos||0).toLocaleString('es-CL');$("invoiceTotal").textContent=posMoney(t.total);$("invoicePaid").textContent=posMoney(t.pagado);$("invoiceBalance").textContent=posMoney(t.saldo);$("invoiceRows").innerHTML=data.rows.length?data.rows.map(row=>{const sent=String(row.venta_estadosii||'')==='DOK',active=!['I','N','X'].includes(String(row.venta_estado||'').toUpperCase()),delivered=String(row.venta_entregado||'').toUpperCase()==='S';return `<tr><td><input type="checkbox" data-invoice-select data-number="${row.venta_numero}" data-type="${row.venta_tipo}" data-local="${escapeHtml(row.local_codigo)}"></td><td class="invoice-row-actions"><button data-invoice-detail="${row.venta_numero}" data-type="${row.venta_tipo}" data-local="${escapeHtml(row.local_codigo)}" title="Ver detalle">Ver</button><button data-invoice-detail="${row.venta_numero}" data-type="${row.venta_tipo}" data-local="${escapeHtml(row.local_codigo)}" title="Copiar">Copiar</button></td><td><span class="invoice-signal ${sent?'ok':'wait'}">${sent?'DOK':'Pend.'}</span></td><td><span class="invoice-signal ${active?'ok':'bad'}">${active?'Activa':'Baja'}</span></td><td><span class="invoice-signal ${delivered?'ok':'wait'}">${delivered?'Si':'No'}</span></td><td>${Number(row.lineas||0)}</td><td class="key-cell">${Number(row.venta_folio||row.venta_numero||0).toLocaleString('es-CL')}</td><td>${escapeHtml(row.venta_fecha||'')}</td><td>${escapeHtml(row.cliente_rut||'')}</td><td class="invoice-client">${escapeHtml(row.cliente_nombre||'')}</td><td>${escapeHtml(row.ruta_nombre||'-')}</td><td><strong>${posMoney(row.venta_totalventa)}</strong></td><td>${posMoney(row.venta_pagototal)}</td><td>${posMoney(row.saldo)}</td><td>${escapeHtml(row.vendedor_nombre||'-')}</td><td>${escapeHtml(row.repartidor_nombre||'-')}</td></tr>`;}).join(''):`<tr><td colspan="16" class="empty">No hay facturas para los filtros seleccionados.</td></tr>`;}
function selectedInvoices(){return [...document.querySelectorAll('[data-invoice-select]:checked')].map(x=>({venta_numero:Number(x.dataset.number),venta_tipo:x.dataset.type,local_codigo:x.dataset.local}));}
async function invoiceAction(action,catalogs){const selected=selectedInvoices(),message=$("invoiceMessage");if(action==='all'){$("invoiceSelectAll").checked=true;$("invoiceSelectAll").dispatchEvent(new Event('change'));return;}if(action==='picking'){navigate('picking');return;}if(action==='new'){openInvoiceMode(action);return;}if(!selected.length){message.textContent='Seleccione al menos una factura.';return;}try{if(action==='date'){const value=prompt('Nueva fecha (AAAA-MM-DD):',new Date().toISOString().slice(0,10));if(!value)return;await saveJson('/api/facturas/cambio-fecha','PUT',{documents:selected,value});message.textContent=`Fecha actualizada en ${selected.length} documentos.`;await loadFacturas();return;}if(action==='delivery'){const value=prompt(`Codigo de repartidor:\n${catalogs.repartidores.slice(0,15).map(x=>`${x.value}: ${x.label}`).join('\n')}`,'');if(value===null)return;await saveJson('/api/facturas/asignar-reparto','PUT',{documents:selected,value});message.textContent=`Reparto asignado a ${selected.length} documentos.`;await loadFacturas();return;}if(action==='send'){await saveJson('/api/facturas/enviar-folios','PUT',{documents:selected});message.textContent=`${selected.length} facturas marcadas para envio de folios.`;await loadFacturas();return;}if(action==='bulk-print'){window.print();return;}message.textContent=`Proceso ${action} preparado para ${selected.length} facturas.`;}catch(error){message.textContent=error.message;}}
function openInvoiceMode(mode){const labels={new:'Nueva factura',manual:'Factura manual',special:'Factura especial'},overlay=$("invoiceDetail");overlay.innerHTML=`<aside class="editor-drawer invoice-create-drawer"><header><div><p class="eyebrow">Emision de documento</p><h3>${labels[mode]}</h3></div><button class="drawer-close">×</button></header><div class="invoice-create-body"><p>Seleccione cliente, productos y condiciones desde el flujo de venta. La numeracion y el guardado usan la estructura de ventas FE/FA.</p><div class="invoice-create-options"><button class="primary" data-open="clientes">Seleccionar cliente</button><button class="ghost" data-open="productos">Buscar productos</button></div></div><footer><button class="ghost drawer-close-footer">Cancelar</button></footer></aside>`;overlay.classList.add('open');const close=()=>overlay.classList.remove('open');overlay.querySelector('.drawer-close').onclick=close;overlay.querySelector('.drawer-close-footer').onclick=close;}
async function openFacturaDetail(local,type,number){const data=await fetchJson(`/api/facturas/${encodeURIComponent(local)}/${encodeURIComponent(type)}/${encodeURIComponent(number)}`,null);if(!data)return;const v=data.venta,overlay=$("invoiceDetail");overlay.innerHTML=`<aside class="editor-drawer boleta-drawer"><header><div><p class="eyebrow">Factura ${escapeHtml(v.venta_tipo)}</p><h3>Folio ${Number(v.venta_folio||v.venta_numero).toLocaleString('es-CL')}</h3></div><button class="drawer-close">×</button></header><div class="boleta-detail-meta"><span><small>Fecha</small><strong>${escapeHtml(v.venta_fecha||'')}</strong></span><span><small>Cliente</small><strong>${escapeHtml(v.cliente_nombre||'')}</strong></span><span><small>RUT</small><strong>${escapeHtml(v.cliente_rut||'')}</strong></span><span><small>Vendedor</small><strong>${escapeHtml(v.vendedor_nombre||'')}</strong></span></div><div class="boleta-detail-scroll"><h4>Productos</h4><table class="detail-table"><thead><tr><th>Codigo</th><th>Producto</th><th>Cantidad</th><th>Neto</th><th>IVA</th><th>ILA</th><th>Total</th></tr></thead><tbody>${data.lineas.map(l=>`<tr><td>${escapeHtml(l.producto_codigo)}</td><td>${escapeHtml(l.producto_descripcion)}</td><td>${clNumber(l.venta_cantidad,3)}</td><td>${posMoney(l.venta_lineaneto)}</td><td>${posMoney(l.venta_lineaiva)}</td><td>${posMoney(l.venta_lineaila)}</td><td><strong>${posMoney(l.total_linea)}</strong></td></tr>`).join('')}</tbody></table><h4>Pagos</h4><div class="boleta-payment-list">${data.pagos.length?data.pagos.map(p=>`<p><span>${escapeHtml(p.fpago_descripcion)}</span><strong>${posMoney(p.venta_pagomonto)}</strong></p>`).join(''):'<p><span>Sin pagos registrados</span><strong>$0</strong></p>'}</div></div><footer><strong class="boleta-grand-total">Total ${posMoney(v.venta_totalventa)}</strong><button class="primary drawer-close-footer">Cerrar</button></footer></aside>`;overlay.classList.add('open');const close=()=>overlay.classList.remove('open');overlay.querySelector('.drawer-close').onclick=close;overlay.querySelector('.drawer-close-footer').onclick=close;}

async function renderNotasVenta(){
  const catalogs=await fetchJson('/api/notas-venta/catalogos',{vendedores:[],rutas:[]}),today=new Date(),start=new Date(today.getFullYear(),today.getMonth(),1).toISOString().slice(0,10),end=today.toISOString().slice(0,10);
  $("content").innerHTML=`<section class="invoice-maintainer nv-maintainer"><header class="maintainer-heading"><div><p class="eyebrow">Ventas / Pedidos NV</p><h2>Mantenedor de notas de venta</h2><p>Gestiona pedidos, fechas de entrega y preparación para facturación.</p></div><button id="nvNew" class="primary" type="button">+ Crear NV</button></header>
  <div class="invoice-filters nv-filters"><label>Emisión desde<input id="nvFrom" type="date" value="${start}"></label><label>Emisión hasta<input id="nvTo" type="date" value="${end}"></label><label>Entrega desde<input id="nvDeliveryFrom" type="date"></label><label>Entrega hasta<input id="nvDeliveryTo" type="date"></label><label>N° pedido<input id="nvNumber" placeholder="Número NV"></label><label>Local<select id="nvLocal"><option value="">Todos</option><option value="01" ${currentLocal.local_codigo==='01'?'selected':''}>01 · Bodega</option><option value="02" ${currentLocal.local_codigo==='02'?'selected':''}>02 · Panadería</option></select></label><label>Vendedor<select id="nvSeller">${optionsHtml(catalogs.vendedores,'','Todos')}</select></label><label>Ruta<select id="nvRoute">${optionsHtml(catalogs.rutas,'','Todas')}</select></label><label>Estado<select id="nvStatus"><option value="">Todos</option><option value="N">Pendientes</option><option value="S">Facturadas</option></select></label><button id="nvSearch" class="primary" type="button">Buscar</button><button id="nvClear" class="ghost" type="button">Limpiar</button></div>
  <div class="invoice-totals"><article><span>Notas de venta</span><strong id="nvCount">0</strong></article><article><span>Facturadas</span><strong id="nvInvoiced">0</strong></article><article><span>Pendientes</span><strong id="nvPending">0</strong></article><article><span>Total pedidos</span><strong id="nvTotal">$0</strong></article><p id="nvMessage"></p></div>
  <div class="invoice-table-wrap"><table class="invoice-table nv-table"><thead><tr><th>Acciones</th><th>Número</th><th>Tipo</th><th>Emisión</th><th>Hora</th><th>Entrega</th><th>Cliente</th><th>Ruta</th><th>Vend.</th><th>Vendedor</th><th>Total</th><th>Estado</th></tr></thead><tbody id="nvRows"><tr><td colspan="12" class="empty">Cargando notas de venta...</td></tr></tbody></table></div></section><div id="nvOverlay" class="editor-overlay" aria-hidden="true"></div>`;
  $("nvNew").onclick=()=>openNvEditor();$("nvSearch").onclick=loadNotasVenta;$("nvClear").onclick=()=>{$("nvNumber").value='';$("nvDeliveryFrom").value='';$("nvDeliveryTo").value='';$("nvSeller").value='';$("nvRoute").value='';$("nvStatus").value='';loadNotasVenta();};
  $("nvRows").onclick=async event=>{const button=event.target.closest('[data-nv-action]');if(!button)return;const local=button.dataset.local,number=button.dataset.number,action=button.dataset.nvAction;if(action==='view')return openNvDetail(local,number);if(action==='edit')return openNvEditor(local,number);if(action==='copy'){if(!confirm(`¿Crear una copia de la NV ${number}?`))return;const result=await saveJson(`/api/notas-venta/${encodeURIComponent(local)}/${number}/copiar`,'POST',{});$("nvMessage").textContent=`Copia creada: NV ${result.venta_numero}.`;return loadNotasVenta();}if(action==='delete'){if(!confirm(`¿Anular la NV ${number}?`))return;await saveJson(`/api/notas-venta/${encodeURIComponent(local)}/${number}`,'DELETE',{});$("nvMessage").textContent=`NV ${number} anulada.`;loadNotasVenta();}};
  await loadNotasVenta();
}

function nvQuery(){const q=new URLSearchParams({fecha_desde:$("nvFrom").value,fecha_hasta:$("nvTo").value,limit:'300'});for(const [id,key] of [['nvDeliveryFrom','entrega_desde'],['nvDeliveryTo','entrega_hasta'],['nvNumber','numero'],['nvLocal','local_codigo'],['nvSeller','vendedor_codigo'],['nvRoute','ruta_id'],['nvStatus','estado']])if($(id).value)q.set(key,$(id).value);return q;}
async function loadNotasVenta(){const data=await fetchJson(`/api/notas-venta?${nvQuery()}`,{rows:[],totals:{}}),t=data.totals||{};$("nvCount").textContent=Number(t.documentos||0).toLocaleString('es-CL');$("nvInvoiced").textContent=Number(t.facturadas||0).toLocaleString('es-CL');$("nvPending").textContent=Number(t.pendientes||0).toLocaleString('es-CL');$("nvTotal").textContent=posMoney(t.total);$("nvRows").innerHTML=data.rows.length?data.rows.map(row=>{const invoiced=String(row.venta_facturado||'N')==='S',inactive=['I','N','X'].includes(String(row.venta_estado||'').toUpperCase());return `<tr class="${inactive?'row-inactive':''}"><td class="invoice-row-actions"><button data-nv-action="view" data-local="${escapeHtml(row.local_codigo)}" data-number="${row.venta_numero}">Ver</button><button data-nv-action="edit" data-local="${escapeHtml(row.local_codigo)}" data-number="${row.venta_numero}" ${invoiced||inactive?'disabled':''}>Editar</button><button data-nv-action="copy" data-local="${escapeHtml(row.local_codigo)}" data-number="${row.venta_numero}">Copiar</button><button data-nv-action="delete" data-local="${escapeHtml(row.local_codigo)}" data-number="${row.venta_numero}" ${invoiced||inactive?'disabled':''}>Anular</button></td><td class="key-cell">${Number(row.venta_numero).toLocaleString('es-CL')}</td><td>NOTA VENTA</td><td>${escapeHtml(row.venta_fecha||'')}</td><td>${escapeHtml(String(row.venta_hora||'').slice(11,16))}</td><td>${escapeHtml(row.venta_fechavto||'-')}</td><td class="invoice-client">${escapeHtml(row.cliente_nombre||'')}<small>${escapeHtml(row.cliente_rut||'')}</small></td><td>${escapeHtml(row.ruta_nombre||'-')}</td><td>${escapeHtml(row.vendedor_codigo||'-')}</td><td>${escapeHtml(row.vendedor_nombre||'-')}</td><td><strong>${posMoney(row.venta_totalventa)}</strong></td><td><span class="invoice-signal ${inactive?'bad':invoiced?'ok':'wait'}">${inactive?'Anulada':invoiced?'Facturada':'Pendiente'}</span></td></tr>`;}).join(''):`<tr><td colspan="12" class="empty">No existen notas de venta para los filtros seleccionados.</td></tr>`;}

async function openNvDetail(local,number){const data=await fetchJson(`/api/notas-venta/${encodeURIComponent(local)}/${number}`,null);if(!data)return;const v=data.venta,overlay=$("nvOverlay");overlay.innerHTML=`<aside class="editor-drawer boleta-drawer"><header><div><p class="eyebrow">Nota de venta</p><h3>NV ${Number(v.venta_numero).toLocaleString('es-CL')}</h3></div><button class="drawer-close">×</button></header><div class="boleta-detail-meta"><span><small>Emisión</small><strong>${escapeHtml(v.venta_fecha||'')}</strong></span><span><small>Entrega</small><strong>${escapeHtml(v.venta_fechavto||'')}</strong></span><span><small>Cliente</small><strong>${escapeHtml(v.cliente_nombre||'')}</strong></span><span><small>Ruta / Vendedor</small><strong>${escapeHtml(v.ruta_nombre||'-')} · ${escapeHtml(v.vendedor_nombre||'-')}</strong></span></div><div class="boleta-detail-scroll"><table class="detail-table"><thead><tr><th>Código</th><th>Producto</th><th>Cantidad</th><th>Desc.</th><th>Neto</th><th>IVA</th><th>ILA</th><th>Total</th></tr></thead><tbody>${data.lineas.map(l=>`<tr><td>${escapeHtml(l.producto_codigo)}</td><td>${escapeHtml(l.producto_descripcion)}</td><td>${clNumber(l.venta_cantidad,3)}</td><td>${clNumber(l.venta_descuentol,2)}%</td><td>${posMoney(l.venta_lineaneto)}</td><td>${posMoney(l.venta_lineaiva)}</td><td>${posMoney(l.venta_lineaila)}</td><td><strong>${posMoney(l.total_linea)}</strong></td></tr>`).join('')}</tbody></table></div><footer><strong class="boleta-grand-total">Total ${posMoney(v.venta_totalventa)}</strong><button class="primary drawer-close-footer">Cerrar</button></footer></aside>`;overlay.classList.add('open');const close=()=>overlay.classList.remove('open');overlay.querySelector('.drawer-close').onclick=close;overlay.querySelector('.drawer-close-footer').onclick=close;}

async function openNvEditor(local='',number=''){const catalogs=await fetchJson('/api/notas-venta/catalogos',{vendedores:[]}),detail=number?await fetchJson(`/api/notas-venta/${encodeURIComponent(local)}/${number}`,null):null,today=new Date().toISOString().slice(0,10),delivery=new Date(Date.now()+3*86400000).toISOString().slice(0,10);nvState={local:local||currentLocal.local_codigo,number:number||'',lines:(detail?.lineas||[]).map(l=>({producto_codigo:l.producto_codigo,producto_descripcion:l.producto_descripcion,cantidad:Number(l.venta_cantidad),descuento:Number(l.venta_descuentol||0),precio_venta:Number(l.total_linea||0)/(Number(l.venta_cantidad)||1)}))};const v=detail?.venta||{},overlay=$("nvOverlay");overlay.innerHTML=`<aside class="editor-drawer nv-editor"><header><div><p class="eyebrow">${number?'Modificar pedido':'Nuevo pedido'}</p><h3>${number?`NV ${Number(number).toLocaleString('es-CL')}`:'Crear nota de venta'}</h3></div><button class="drawer-close">×</button></header><div class="nv-editor-body"><div class="nv-header-fields"><label>Local<select id="nvEditLocal" ${number?'disabled':''}><option value="01" ${nvState.local==='01'?'selected':''}>01 · Bodega</option><option value="02" ${nvState.local==='02'?'selected':''}>02 · Panadería</option></select></label><label>Emisión<input id="nvEditDate" type="date" value="${v.venta_fecha||today}"></label><label>Entrega<input id="nvEditDelivery" type="date" value="${v.venta_fechavto||delivery}"></label><label>Vendedor<select id="nvEditSeller">${optionsHtml(catalogs.vendedores,v.vendedor_codigo||currentUser.vendedor_codigo||'','Seleccione')}</select></label></div><div class="nv-client-search"><label>Cliente<input id="nvClient" value="${escapeHtml(v.cliente_rut||'')}" placeholder="RUT o nombre"></label><button id="nvFindClient" class="ghost">Buscar cliente</button><strong id="nvClientName">${escapeHtml(v.cliente_nombre||'')}</strong><div id="nvClientResults"></div></div><div class="nv-product-search"><input id="nvProductSearch" placeholder="Código, barra o descripción"><button id="nvFindProduct" class="primary">Agregar producto</button><div id="nvProductResults"></div></div><div class="invoice-table-wrap"><table class="invoice-table"><thead><tr><th>Código</th><th>Producto</th><th>Cantidad</th><th>Descuento %</th><th>Precio</th><th>Total</th><th></th></tr></thead><tbody id="nvEditLines"></tbody></table></div><label class="nv-observation">Observación<textarea id="nvObservation">${escapeHtml(v.venta_observacion01||'')}</textarea></label><p id="nvSaveMessage"></p></div><footer><strong id="nvEditorTotal">Total $0</strong><button class="ghost drawer-close-footer">Cancelar</button><button id="nvSave" class="primary">Guardar NV</button></footer></aside>`;overlay.classList.add('open');const close=()=>overlay.classList.remove('open');overlay.querySelector('.drawer-close').onclick=close;overlay.querySelector('.drawer-close-footer').onclick=close;$("nvFindClient").onclick=nvFindClients;$("nvFindProduct").onclick=nvFindProducts;$("nvEditLines").oninput=event=>{const input=event.target.closest('[data-nv-line]');if(!input)return;nvState.lines[Number(input.dataset.index)][input.dataset.nvLine]=Number(input.value||0);nvRenderEditorLines();};$("nvEditLines").onclick=event=>{const button=event.target.closest('[data-nv-remove]');if(button){nvState.lines.splice(Number(button.dataset.nvRemove),1);nvRenderEditorLines();}};$("nvSave").onclick=saveNvEditor;nvRenderEditorLines();}

async function nvFindClients(){const q=$("nvClient").value.trim(),params=new URLSearchParams({limit:'12'});if(q.includes('-')||/^\d+$/.test(q))params.set('rut',q);else params.set('nombre',q);const rows=await fetchJson(`/api/clientes?${params}`,[]);$("nvClientResults").innerHTML=rows.map(c=>`<button type="button" data-nv-client="${escapeHtml(c.cliente_rut)}" data-name="${escapeHtml(c.cliente_nombre)}"><b>${escapeHtml(c.cliente_rut)}</b><span>${escapeHtml(c.cliente_nombre)}</span></button>`).join('')||'<span>Sin coincidencias</span>';$("nvClientResults").onclick=event=>{const button=event.target.closest('[data-nv-client]');if(!button)return;$("nvClient").value=button.dataset.nvClient;$("nvClientName").textContent=button.dataset.name;$("nvClientResults").innerHTML='';};}
async function nvFindProducts(){const q=$("nvProductSearch").value.trim();if(!q)return;const rows=await fetchJson(`/api/pos/productos?q=${encodeURIComponent(q)}&local_codigo=${encodeURIComponent($("nvEditLocal").value)}`,[]);$("nvProductResults").innerHTML=rows.map(p=>`<button type="button" data-nv-product="${escapeHtml(p.producto_codigo)}" data-description="${escapeHtml(p.producto_descripcion)}" data-price="${Number(p.precio_venta||0)}"><b>${escapeHtml(p.producto_codigo)}</b><span>${escapeHtml(p.producto_descripcion)}</span><strong>${posMoney(p.precio_venta)}</strong></button>`).join('')||'<span>Sin coincidencias</span>';$("nvProductResults").onclick=event=>{const button=event.target.closest('[data-nv-product]');if(!button)return;const existing=nvState.lines.find(x=>x.producto_codigo===button.dataset.nvProduct);if(existing)existing.cantidad+=1;else nvState.lines.push({producto_codigo:button.dataset.nvProduct,producto_descripcion:button.dataset.description,cantidad:1,descuento:0,precio_venta:Number(button.dataset.price)});$("nvProductResults").innerHTML='';$("nvProductSearch").value='';nvRenderEditorLines();};}
function nvRenderEditorLines(){const body=$("nvEditLines");if(!body)return;body.innerHTML=nvState.lines.length?nvState.lines.map((l,i)=>`<tr><td class="key-cell">${escapeHtml(l.producto_codigo)}</td><td>${escapeHtml(l.producto_descripcion)}</td><td><input data-nv-line="cantidad" data-index="${i}" type="number" min="0.001" step="0.001" value="${l.cantidad}"></td><td><input data-nv-line="descuento" data-index="${i}" type="number" min="0" max="100" step="0.01" value="${l.descuento}"></td><td>${posMoney(l.precio_venta)}</td><td><strong>${posMoney(Number(l.precio_venta)*Number(l.cantidad)*(1-Number(l.descuento)/100))}</strong></td><td><button data-nv-remove="${i}" class="tool-btn danger">Quitar</button></td></tr>`).join(''):'<tr><td colspan="7" class="empty">Agregue productos a la nota de venta.</td></tr>';$("nvEditorTotal").textContent=`Total ${posMoney(nvState.lines.reduce((sum,l)=>sum+Number(l.precio_venta)*Number(l.cantidad)*(1-Number(l.descuento)/100),0))}`;}
async function saveNvEditor(){const message=$("nvSaveMessage"),button=$("nvSave");button.disabled=true;try{const payload={local_codigo:$("nvEditLocal").value,user_id:Number(currentUser.user_id),cliente_rut:$("nvClient").value.trim(),vendedor_codigo:$("nvEditSeller").value,fecha_emision:$("nvEditDate").value,fecha_entrega:$("nvEditDelivery").value,observacion:$("nvObservation").value,lines:nvState.lines.map(l=>({producto_codigo:l.producto_codigo,cantidad:Number(l.cantidad),descuento:Number(l.descuento) }))};const result=await saveJson(nvState.number?`/api/notas-venta/${nvState.number}`:'/api/notas-venta',nvState.number?'PUT':'POST',payload);$("nvOverlay").classList.remove('open');$("nvMessage").textContent=`NV ${result.venta_numero} guardada correctamente.`;loadNotasVenta();}catch(error){message.textContent=error.message;}finally{button.disabled=false;}}

const reportGroups={
  comercial:[['estadisticas','Estadisticas de ventas'],['vendedores','Ventas por vendedor'],['rutas','Ventas por rutas'],['familias','Ventas por familias'],['formas-pago','Formas de pago'],['productos','Ranking de productos']],
  cobranza:[['pendientes','Facturas pendientes'],['cta-cte','Cuentas corrientes'],['cobros','Cobros y recaudacion'],['cartola','Cartola de clientes']],
  abastecimiento:[['compras','Compras por proveedor'],['inventario','Inventario valorizado'],['stock-bajo','Stock critico']]
};
let reportState={id:'estadisticas',data:null,catalogs:null};
async function renderReportCenter(reportId='estadisticas'){
  reportState.id=reportId;reportState.catalogs=reportState.catalogs||await fetchJson('/api/reportes/catalogos',{vendedores:[],rutas:[],familias:[],reportes:[]});const today=new Date(),from=new Date(today.getFullYear(),today.getMonth(),1).toISOString().slice(0,10),to=today.toISOString().slice(0,10),definition=reportState.catalogs.reportes.find(x=>x.id===reportId)||{};
  $("content").innerHTML=`<section class="report-center"><header class="report-heading"><div><p class="eyebrow">Inteligencia de gestion</p><h2>Centro de reportes</h2><p>Informacion comercial, financiera y operacional para la toma de decisiones.</p></div><div class="report-heading-actions"><button id="reportPrint" class="ghost">Imprimir</button><button id="reportCsv" class="primary">Exportar CSV</button></div></header><div class="report-layout"><aside class="report-catalog"><label>Buscar informe<input id="reportFinder" placeholder="Nombre del reporte"></label>${Object.entries(reportGroups).map(([group,items])=>`<section><h3>${group==='comercial'?'Ventas y gestion':group==='cobranza'?'Clientes y cobranza':'Compras e inventario'}</h3>${items.map(([id,label])=>`<button class="${id===reportId?'active':''}" data-report-id="${id}"><span>${reportIcon(id)}</span>${label}</button>`).join('')}</section>`).join('')}</aside><main class="report-workspace"><header class="report-title"><div><p id="reportArea">Reporte gerencial</p><h2 id="reportName">${escapeHtml(definition.title||'Reporte')}</h2><span id="reportDescription">${escapeHtml(definition.description||'')}</span></div><em id="reportUpdated"></em></header><div class="report-filters"><label>Desde<input id="reportFrom" type="date" value="${from}"></label><label>Hasta<input id="reportTo" type="date" value="${to}"></label><label>Local<select id="reportLocal"><option value="">Todos</option><option value="01" ${currentLocal.local_codigo==='01'?'selected':''}>01 · Bodega</option><option value="02" ${currentLocal.local_codigo==='02'?'selected':''}>02 · Panaderia</option></select></label><label>Vendedor<select id="reportSeller">${optionsHtml(reportState.catalogs.vendedores,'','Todos')}</select></label><label>Ruta<select id="reportRoute">${optionsHtml(reportState.catalogs.rutas,'','Todas')}</select></label><label>Familia<select id="reportFamily">${optionsHtml(reportState.catalogs.familias,'','Todas')}</select></label><label>Cliente<input id="reportClient" placeholder="RUT cliente"></label><button id="reportRun" class="primary">Generar</button><button id="reportReset" class="ghost">Limpiar</button></div><div id="reportNeedsClient" class="report-notice"></div><div id="reportKpis" class="report-kpis"></div><section class="report-chart-card"><header><div><p>Vista comparativa</p><h3 id="reportChartTitle">Principales resultados</h3></div><span id="reportRowCount"></span></header><div id="reportChart" class="report-bars"><span>Cargando datos...</span></div></section><section class="report-table-card"><div class="report-table-toolbar"><strong>Detalle del informe</strong><input id="reportTableSearch" placeholder="Filtrar resultados"></div><div class="report-table-wrap"><table id="reportTable"><thead></thead><tbody><tr><td>Cargando reporte...</td></tr></tbody><tfoot></tfoot></table></div></section></main></div></section>`;
  document.querySelector('.report-catalog').onclick=event=>{const button=event.target.closest('[data-report-id]');if(button)renderReportCenter(button.dataset.reportId);};$("reportFinder").oninput=event=>document.querySelectorAll('[data-report-id]').forEach(button=>button.hidden=!button.textContent.toLowerCase().includes(event.target.value.toLowerCase()));$("reportRun").onclick=loadReport;$("reportReset").onclick=()=>{$("reportSeller").value='';$("reportRoute").value='';$("reportFamily").value='';$("reportClient").value='';loadReport();};$("reportPrint").onclick=()=>window.print();$("reportCsv").onclick=exportReportCsv;$("reportTableSearch").oninput=filterReportRows;await loadReport();
}
function reportIcon(id){if(['estadisticas','vendedores','rutas','familias','formas-pago','productos'].includes(id))return 'VE';if(['pendientes','cta-cte','cobros','cartola'].includes(id))return 'CC';return 'IN';}
function reportQuery(){const q=new URLSearchParams({fecha_desde:$("reportFrom").value,fecha_hasta:$("reportTo").value,limit:'500'});for(const [id,key] of [['reportLocal','local_codigo'],['reportSeller','vendedor_codigo'],['reportRoute','ruta_id'],['reportFamily','familia_codigo'],['reportClient','cliente_rut']])if($(id).value.trim())q.set(key,$(id).value.trim());return q;}
async function loadReport(){const definition=reportState.catalogs.reportes.find(x=>x.id===reportState.id)||{},notice=$("reportNeedsClient");notice.textContent=definition.needs_client&&!$("reportClient").value.trim()?'Ingrese el RUT del cliente para generar su cartola.':'';notice.classList.toggle('visible',Boolean(notice.textContent));const data=await fetchJson(`/api/reportes/${encodeURIComponent(reportState.id)}?${reportQuery()}`,{definition,rows:[],totals:{},count:0});reportState.data=data;$("reportName").textContent=data.definition.title;$("reportDescription").textContent=data.definition.description;$("reportUpdated").textContent=`Actualizado ${new Date().toLocaleTimeString('es-CL',{hour:'2-digit',minute:'2-digit'})}`;renderReportKpis(data);renderReportChart(data);renderReportTable(data);}
function renderReportKpis(data){const moneyKeys=data.definition.money||[],primary=moneyKeys.includes('total')?'total':moneyKeys[0],secondary=moneyKeys.find(x=>x!==primary),sum=key=>Number(data.totals?.[key]||0);$("reportKpis").innerHTML=`<article><span>Registros</span><strong>${Number(data.count||0).toLocaleString('es-CL')}</strong><small>Filas obtenidas</small></article>${primary?`<article><span>${reportColumnLabel(data,primary)}</span><strong>${posMoney(sum(primary))}</strong><small>Total del informe</small></article>`:''}${secondary?`<article><span>${reportColumnLabel(data,secondary)}</span><strong>${posMoney(sum(secondary))}</strong><small>Acumulado</small></article>`:''}<article><span>Periodo</span><strong>${$("reportFrom").value.slice(5).replace('-','/')} - ${$("reportTo").value.slice(5).replace('-','/')}</strong><small>${$("reportLocal").selectedOptions[0].text}</small></article>`;}
function reportColumnLabel(data,key){return data.definition.columns.find(x=>x[0]===key)?.[1]||key;}
function renderReportChart(data){const key=data.definition.chart,rows=(data.rows||[]).slice(0,18),max=Math.max(...rows.map(x=>Math.abs(Number(x[key]||0))),1),labelKey=data.definition.chart_label||data.definition.columns[0]?.[0];$("reportChartTitle").textContent=`${reportColumnLabel(data,key)} por ${reportColumnLabel(data,labelKey)}`;$("reportRowCount").textContent=`${data.count} resultados`;$("reportChart").innerHTML=rows.length?rows.map(row=>`<div class="report-bar"><span title="${escapeHtml(row[labelKey])}">${escapeHtml(String(row[labelKey]??'-'))}</span><i><b style="width:${Math.max(2,Math.abs(Number(row[key]||0))*100/max)}%"></b></i><strong>${(data.definition.money||[]).includes(key)?posMoney(row[key]):Number(row[key]||0).toLocaleString('es-CL')}</strong></div>`).join(''):'<span class="empty">No existen datos para los filtros seleccionados.</span>';}
function renderReportTable(data){const columns=data.definition.columns||[],moneyKeys=new Set(data.definition.money||[]),head=$("reportTable").querySelector('thead'),body=$("reportTable").querySelector('tbody'),foot=$("reportTable").querySelector('tfoot');head.innerHTML=`<tr>${columns.map(c=>`<th>${escapeHtml(c[1])}</th>`).join('')}</tr>`;body.innerHTML=data.rows.length?data.rows.map(row=>`<tr>${columns.map(([key])=>`<td data-key="${key}" ${moneyKeys.has(key)?'class="numeric"':''}>${moneyKeys.has(key)?posMoney(row[key]):escapeHtml(formatReportValue(row[key]))}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${columns.length}" class="empty">Sin información para el periodo.</td></tr>`;foot.innerHTML=data.rows.length?`<tr>${columns.map(([key],i)=>`<td ${moneyKeys.has(key)?'class="numeric"':''}>${i===0?'TOTAL':moneyKeys.has(key)?posMoney(data.totals[key]||0):''}</td>`).join('')}</tr>`:'';}
function formatReportValue(value){if(value===null||value===undefined)return '-';if(typeof value==='number')return value.toLocaleString('es-CL',{maximumFractionDigits:3});return String(value);}
function filterReportRows(){const term=$("reportTableSearch").value.toLowerCase();$("reportTable").querySelectorAll('tbody tr').forEach(row=>row.hidden=!row.textContent.toLowerCase().includes(term));}
function exportReportCsv(){const data=reportState.data;if(!data?.rows?.length)return;const columns=data.definition.columns,quote=value=>`"${String(value??'').replaceAll('"','""')}"`,lines=[columns.map(x=>quote(x[1])).join(';'),...data.rows.map(row=>columns.map(([key])=>quote(row[key])).join(';'))],blob=new Blob(['\ufeff'+lines.join('\r\n')],{type:'text/csv;charset=utf-8'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`${reportState.id}-${$("reportFrom").value}-${$("reportTo").value}.csv`;a.click();URL.revokeObjectURL(url);}

let managementState={mode:'kpi',data:null};
async function renderManagement(mode='kpi'){
  managementState.mode=mode;const now=new Date(),months=['Enero','Febrero','Marzo','Abril','Mayo','Junio','Julio','Agosto','Septiembre','Octubre','Noviembre','Diciembre'];
  $("content").innerHTML=`<section class="management-dashboard"><header class="management-heading"><div><p class="eyebrow">Gerencia / Inteligencia comercial</p><h2>Panel gerencial de ventas</h2><p>Neto, total, cobros y desempeño comercial consolidado.</p></div><div class="management-actions"><button id="managementExcel" class="ghost">Exportar Excel</button><button id="managementPdf" class="primary">Exportar PDF</button></div></header><div class="management-tabs"><button data-management-tab="kpi" class="${mode==='kpi'?'active':''}">Indicadores KPI</button><button data-management-tab="margen" class="${mode==='margen'?'active':''}">Margenes</button><button data-management-tab="rutas" class="${mode==='rutas'?'active':''}">Rendimiento rutas</button><button data-management-tab="caja" class="${mode==='caja'?'active':''}">Caja y bancos</button></div><div class="management-filters"><label>Mes<select id="managementMonth">${months.map((x,i)=>`<option value="${i+1}" ${i===now.getMonth()?'selected':''}>${x}</option>`).join('')}</select></label><label>Año<select id="managementYear">${[now.getFullYear(),now.getFullYear()-1,now.getFullYear()-2,now.getFullYear()-3].map(y=>`<option>${y}</option>`).join('')}</select></label><label>Local<select id="managementLocal"><option value="">Todos los locales</option><option value="01" ${currentLocal.local_codigo==='01'?'selected':''}>01 · Bodega</option><option value="02" ${currentLocal.local_codigo==='02'?'selected':''}>02 · Panaderia</option></select></label><button id="managementRun" class="primary">Actualizar</button></div><div id="managementKpis" class="management-kpis"></div><div id="managementCharts" class="management-grid"><div class="management-loading">Generando indicadores...</div></div></section>`;
  document.querySelector('.management-tabs').onclick=event=>{const button=event.target.closest('[data-management-tab]');if(button){managementState.mode=button.dataset.managementTab;document.querySelectorAll('[data-management-tab]').forEach(x=>x.classList.toggle('active',x===button));renderManagementCharts();}};$("managementRun").onclick=loadManagement;$("managementExcel").onclick=()=>downloadManagement('excel');$("managementPdf").onclick=()=>downloadManagement('pdf');await loadManagement();
}
function managementQuery(){return new URLSearchParams({year:$("managementYear").value,month:$("managementMonth").value,...($("managementLocal").value?{local_codigo:$("managementLocal").value}:{})});}
async function loadManagement(){managementState.data=await fetchJson(`/api/gerencia/dashboard?${managementQuery()}`,null);if(!managementState.data)return;const s=managementState.data.summary||{};$("managementKpis").innerHTML=`${managementKpi('Neto',s.neto,'Base imponible','neutral')}${managementKpi('Total',s.total,'Venta del periodo','primary')}${managementKpi('Pagado',s.pagado,'Recaudacion registrada','good')}${managementKpi('Saldo',s.saldo,'Pendiente de cobro',Number(s.saldo)>0?'warn':'good')}${managementKpi('Documentos',s.documentos,'Facturas, boletas y NC','count')}`;renderManagementCharts();}
function managementKpi(label,value,detail,tone){return `<article class="${tone}"><span>${label}</span><strong>${tone==='count'?Number(value||0).toLocaleString('es-CL'):posMoney(value)}</strong><small>${detail}</small></article>`;}
function managementChart(title,subtitle,rows,key='total',type='bar'){const max=Math.max(...rows.map(x=>Math.abs(Number(x[key]||0))),1);return `<article class="management-chart ${type}"><header><div><p>${escapeHtml(subtitle)}</p><h3>${escapeHtml(title)}</h3></div></header><div class="management-chart-body">${rows.length?rows.map(row=>`<div class="management-chart-row"><span title="${escapeHtml(row.label||row.codigo||'')}">${escapeHtml(row.label||row.codigo||'-')}</span><i><b style="width:${Math.max(2,Math.abs(Number(row[key]||0))*100/max)}%"></b></i><strong>${posMoney(row[key])}</strong></div>`).join(''):'<span class="empty">Sin datos para el periodo.</span>'}</div></article>`;}
function managementComparison(data){const months=['Ene','Feb','Mar','Abr','May','Jun','Jul','Ago','Sep','Oct','Nov','Dic'],current=data.comparison?.[1]||{values:{}},previous=data.comparison?.[0]||{values:{}},max=Math.max(...Object.values(current.values||{}).map(Number),...Object.values(previous.values||{}).map(Number),1);return `<article class="management-chart comparison"><header><div><p>Tendencia anual</p><h3>${current.year} comparado con ${previous.year}</h3></div><div class="management-legend"><span>Actual</span><span>Anterior</span></div></header><div class="comparison-bars">${months.map((m,i)=>`<div><i><b style="height:${Number(current.values[String(i+1)]||0)*100/max}%"></b><em style="height:${Number(previous.values[String(i+1)]||0)*100/max}%"></em></i><span>${m}</span></div>`).join('')}</div></article>`;}
function renderManagementCharts(){const d=managementState.data;if(!d)return;let charts=[];if(managementState.mode==='kpi')charts=[managementChart('Ventas diarias','Neto, total y pagado',d.daily,'total'),managementChart('Ventas por local','Consolidado del periodo',d.locals),managementChart('Tipos de documento','Facturas, boletas y notas de credito',d.documents),managementChart('Ventas mensuales por vendedor','Ranking comercial',d.sellers),managementComparison(d),managementChart('Familias con mayor venta','Mix de productos',d.families)];else if(managementState.mode==='margen')charts=[managementChart('Neto por vendedor','Base imponible comercial',d.sellers,'neto'),managementChart('Venta por familias','Contribucion por categoria',d.families,'neto'),managementChart('Neto por local','Comparacion de sucursales',d.locals,'neto'),managementComparison(d)];else if(managementState.mode==='rutas')charts=[managementChart('Ventas por ruta','Cobertura comercial',d.routes),managementChart('Cobrado por ruta','Recuperacion de ventas',d.routes,'pagado'),managementChart('Vendedores del periodo','Responsables comerciales',d.sellers),managementComparison(d)];else charts=[managementChart('Formas de pago','Recaudacion por medio',d.payments),managementChart('Pagado por vendedor','Cobranza comercial',d.sellers,'pagado'),managementChart('Pagado por local','Recaudacion por sucursal',d.locals,'pagado'),managementComparison(d)];$("managementCharts").innerHTML=charts.join('');}
function downloadManagement(format){const q=managementQuery();window.location.href=`/api/gerencia/export/${format}?${q}`;}

let purchaseState={catalogs:null,status:''};
async function renderPurchases(status=''){
  purchaseState.status=status;purchaseState.catalogs=purchaseState.catalogs||await fetchJson('/api/compras/catalogos',{proveedores:[]});const today=new Date(),from=new Date(today.getTime()-30*86400000).toISOString().slice(0,10),to=today.toISOString().slice(0,10);
  $("content").innerHTML=`<section class="purchases-maintainer"><header class="maintainer-heading"><div><p class="eyebrow">Compras / Abastecimiento</p><h2>Mantenedor de documentos de compra</h2><p>Consulta documentos, revisa sus productos y cierra la recepción contra inventario.</p></div></header><div class="purchase-filters"><label>Desde<input id="purchaseFrom" type="date" value="${from}"></label><label>Hasta<input id="purchaseTo" type="date" value="${to}"></label><label>Número<input id="purchaseNumber" placeholder="Documento"></label><label>Proveedor<select id="purchaseProvider">${optionsHtml(purchaseState.catalogs.proveedores,'','Todos')}</select></label><label>Local<select id="purchaseLocal"><option value="">Todos</option><option value="01" ${currentLocal.local_codigo==='01'?'selected':''}>01 · Bodega</option><option value="02" ${currentLocal.local_codigo==='02'?'selected':''}>02 · Panaderia</option></select></label><label>Estado<select id="purchaseStatus"><option value="">Todos</option><option value="A" ${status==='A'?'selected':''}>Abiertas</option><option value="C">Cerradas</option></select></label><button id="purchaseSearch" class="primary">Buscar</button><button id="purchaseClear" class="ghost">Limpiar</button></div><div class="purchase-summary"><article><span>Documentos</span><strong id="purchaseCount">0</strong></article><article><span>Total compras</span><strong id="purchaseTotal">$0</strong></article><article><span>Pagado</span><strong id="purchasePaid">$0</strong></article><article><span>Por pagar</span><strong id="purchaseBalance">$0</strong></article><p id="purchaseMessage"></p></div><div class="purchase-table-wrap"><table class="purchase-table"><thead><tr><th>Acciones</th><th>Número</th><th>Tipo</th><th>Local</th><th>Fecha</th><th>Proveedor</th><th>Líneas</th><th>Total</th><th>Pago</th><th>Saldo</th><th>Estado</th><th>Inventario</th></tr></thead><tbody id="purchaseRows"><tr><td colspan="12" class="empty">Cargando compras...</td></tr></tbody></table></div></section><div id="purchaseOverlay" class="editor-overlay"></div>`;
  $("purchaseSearch").onclick=loadPurchases;$("purchaseClear").onclick=()=>{$("purchaseNumber").value='';$("purchaseProvider").value='';$("purchaseStatus").value='';loadPurchases();};$("purchaseRows").onclick=event=>{const button=event.target.closest('[data-purchase-action]');if(!button)return;openPurchaseDetail(button.dataset.local,button.dataset.type,button.dataset.provider,button.dataset.number);};await loadPurchases();
}
function purchaseQuery(){const q=new URLSearchParams({fecha_desde:$("purchaseFrom").value,fecha_hasta:$("purchaseTo").value,limit:'400'});for(const [id,key] of [['purchaseNumber','numero'],['purchaseProvider','proveedor_codigo'],['purchaseLocal','local_codigo'],['purchaseStatus','estado']])if($(id).value)q.set(key,$(id).value);return q;}
async function loadPurchases(){const rows=await fetchJson(`/api/compras?${purchaseQuery()}`,[]),total=rows.reduce((s,x)=>s+Number(x.compra_totalcompra||0),0),paid=rows.reduce((s,x)=>s+Number(x.pagado||0),0);$("purchaseCount").textContent=rows.length.toLocaleString('es-CL');$("purchaseTotal").textContent=posMoney(total);$("purchasePaid").textContent=posMoney(paid);$("purchaseBalance").textContent=posMoney(total-paid);$("purchaseRows").innerHTML=rows.length?rows.map(row=>`<tr><td><button class="row-edit" data-purchase-action="view" data-number="${row.compra_numero}" data-type="${escapeHtml(row.compra_tipo)}" data-local="${escapeHtml(row.local_codigo)}" data-provider="${escapeHtml(row.proveedor_codigo)}">Ver detalle</button></td><td class="key-cell">${Number(row.compra_numero).toLocaleString('es-CL')}</td><td>${escapeHtml(row.compra_tipo)}</td><td>${escapeHtml(row.local_nombre)}</td><td>${escapeHtml(row.compra_fecha)}</td><td>${escapeHtml(row.proveedor_nombre)}</td><td>${Number(row.lineas||0)}</td><td><strong>${posMoney(row.compra_totalcompra)}</strong></td><td>${posMoney(row.pagado)}</td><td>${posMoney(Number(row.compra_totalcompra||0)-Number(row.pagado||0))}</td><td><span class="invoice-signal ${row.compra_estado==='C'?'ok':'wait'}">${row.compra_estado==='C'?'Cerrada':'Abierta'}</span></td><td><span class="invoice-signal ${row.inventariado?'ok':'wait'}">${row.inventariado?'Ingresada':'Pendiente'}</span></td></tr>`).join(''):`<tr><td colspan="12" class="empty">No existen compras para los filtros seleccionados.</td></tr>`;}
async function openPurchaseDetail(local,type,provider,number){const data=await fetchJson(`/api/compras/${encodeURIComponent(local)}/${encodeURIComponent(type)}/${encodeURIComponent(provider)}/${number}`,null);if(!data)return;const c=data.compra,overlay=$("purchaseOverlay"),closed=String(c.compra_estado)==='C';overlay.innerHTML=`<aside class="editor-drawer purchase-drawer"><header><div><p class="eyebrow">Información del proveedor</p><h3>${escapeHtml(c.proveedor_nombre)}</h3></div><button class="drawer-close">×</button></header><div class="purchase-meta"><span><small>Número</small><strong>${Number(c.compra_numero).toLocaleString('es-CL')}</strong></span><span><small>Tipo</small><strong>${escapeHtml(c.compra_tipo)}</strong></span><span><small>Local</small><strong>${escapeHtml(c.local_nombre)}</strong></span><span><small>Fecha</small><strong>${escapeHtml(c.compra_fecha)}</strong></span><span><small>RUT proveedor</small><strong>${escapeHtml(c.proveedor_codigo)}</strong></span><span><small>Estado</small><strong>${closed?'Cerrada':'Abierta'}</strong></span></div><div class="purchase-detail-scroll"><table class="purchase-detail-table"><thead><tr><th>Código</th><th>Cód. proveedor</th><th>Descripción</th><th>Bodega</th><th>Costo</th><th>Neto</th><th>Cantidad</th><th>UXE</th><th>Precio venta</th><th>Total</th></tr></thead><tbody>${data.lineas.map(l=>`<tr><td>${escapeHtml(l.producto_codigo)}</td><td>${escapeHtml(l.compra_codigoprov||'-')}</td><td>${escapeHtml(l.producto_descripcion)}</td><td>${escapeHtml(l.bodega_nombre||l.bodega_codigo)}</td><td>${posMoney(l.compra_valor)}</td><td>${posMoney(l.compra_pespecial)}</td><td>${clNumber(l.compra_cantidadt||l.compra_cantidad,3)}</td><td>${clNumber(l.compra_uxe,2)}</td><td>${posMoney(l.compra_venta)}</td><td><strong>${posMoney(l.total_linea)}</strong></td></tr>`).join('')}</tbody></table></div><div class="purchase-totals"><span>Subtotal <strong>${posMoney(c.compra_neto)}</strong></span><span>IVA <strong>${posMoney(c.compra_iva)}</strong></span><span>ILA <strong>${posMoney(c.compra_ila)}</strong></span><span>Total compra <strong>${posMoney(c.compra_totalcompra)}</strong></span></div><p id="purchaseCloseMessage"></p><footer><button class="ghost drawer-close-footer">Cerrar ventana</button>${closed?'':`<button id="purchaseClose" class="primary">Cerrar compra e ingresar inventario</button>`}</footer></aside>`;overlay.classList.add('open');const close=()=>overlay.classList.remove('open');overlay.querySelector('.drawer-close').onclick=close;overlay.querySelector('.drawer-close-footer').onclick=close;if(!closed)$("purchaseClose").onclick=async()=>{if(!confirm(`La compra ${number} se cerrará e ingresará al inventario. ¿Continuar?`))return;const button=$("purchaseClose");button.disabled=true;try{const result=await saveJson(`/api/compras/${encodeURIComponent(local)}/${encodeURIComponent(type)}/${encodeURIComponent(provider)}/${number}/cerrar`,'POST',{user_id:Number(currentUser.user_id)});$("purchaseCloseMessage").textContent=`Compra cerrada. ${result.lineas||0} productos y ${clNumber(result.cantidad||0,3)} unidades ingresadas.`;button.remove();loadPurchases();}catch(error){$("purchaseCloseMessage").textContent=error.message;button.disabled=false;}};}

async function renderRoles() {
  const data=await fetchJson('/api/roles',{roles:[],users:[],modules:[]});
  $("content").innerHTML=`<section class="roles-page"><header class="maintainer-heading"><div><p class="eyebrow">Seguridad / Administracion</p><h2>Roles y permisos</h2><p>Define las opciones visibles para cada perfil y asigna un rol a cada usuario.</p></div><button id="newRole" class="primary" type="button">+ Nuevo rol</button></header>
  <div class="roles-layout"><aside class="roles-list">${data.roles.map(role=>`<button type="button" data-role-id="${role.role_id}" class="${role.role_id===1?'active':''}"><span><strong>${escapeHtml(role.role_name)}</strong><small>${role.is_admin==='S'?'Acceso total':`${role.permissions.length} opciones`}</small></span><b>›</b></button>`).join('')}</aside><section id="roleEditor" class="role-editor"></section></div>
  <section class="user-role-panel"><div class="section-head compact"><div><p class="eyebrow">Usuarios</p><h2>Asignacion de perfiles</h2></div></div><div class="user-role-grid">${data.users.map(user=>`<label><span>${escapeHtml(user.user_name||`Usuario ${user.user_id}`)}</span><select data-user-role="${user.user_id}">${data.roles.map(role=>`<option value="${role.role_id}" ${Number(user.role_id||0)===Number(role.role_id)?'selected':''}>${escapeHtml(role.role_name)}</option>`).join('')}</select></label>`).join('')}</div></section></section>`;
  const editRole=role=>{const admin=role?.is_admin==='S';$("roleEditor").innerHTML=`<div class="role-editor-head"><div><p class="eyebrow">Configuracion</p><h3>${role?escapeHtml(role.role_name):'Nuevo rol'}</h3></div></div><label>Nombre del rol<input id="roleName" value="${escapeHtml(role?.role_name||'')}"></label><label class="role-admin"><input id="roleAdmin" type="checkbox" ${admin?'checked':''}> Acceso completo de administrador</label><div class="permission-groups">${[...new Set(data.modules.map(m=>m.group))].map(group=>`<fieldset><legend>${escapeHtml(group)}</legend>${data.modules.filter(m=>m.group===group).map(m=>`<label><input type="checkbox" data-permission="${m.id}" ${admin||role?.permissions.includes(m.id)?'checked':''} ${admin?'disabled':''}> ${escapeHtml(m.label)}</label>`).join('')}</fieldset>`).join('')}</div><p id="roleStatus" class="save-status"></p><button id="saveRole" class="primary" type="button">Guardar rol</button>`;$("roleAdmin").onchange=()=>document.querySelectorAll('[data-permission]').forEach(x=>{x.disabled=$("roleAdmin").checked;if($("roleAdmin").checked)x.checked=true;});$("saveRole").onclick=async()=>{try{const body={role_name:$("roleName").value,is_admin:$("roleAdmin").checked,permissions:[...document.querySelectorAll('[data-permission]:checked')].map(x=>x.dataset.permission)};await saveJson(role?`/api/roles/${role.role_id}`:'/api/roles',role?'PUT':'POST',body);renderRoles();}catch(error){$("roleStatus").textContent=error.message;}};};
  document.querySelector('.roles-list')?.addEventListener('click',event=>{const button=event.target.closest('[data-role-id]');if(!button)return;document.querySelectorAll('.roles-list button').forEach(x=>x.classList.toggle('active',x===button));editRole(data.roles.find(x=>Number(x.role_id)===Number(button.dataset.roleId)));});
  $("newRole").onclick=()=>editRole(null);document.querySelectorAll('[data-user-role]').forEach(select=>select.onchange=async()=>{await saveJson('/api/roles/usuario/asignar','PUT',{user_id:Number(select.dataset.userRole),role_id:Number(select.value)});});editRole(data.roles[0]||null);
}

async function renderBoletas() {
  const catalogs = await fetchJson(`/api/pos/catalogos?local_codigo=${encodeURIComponent(currentLocal.local_codigo)}`, {formas_pago:[],vendedores:[]});
  const today = new Date();
  const monthStart = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().slice(0,10);
  $("content").innerHTML = `
    <section class="boletas-maintainer">
      <header class="maintainer-heading"><div><p class="eyebrow">Ventas / Documento BO</p><h2>Mantenedor de boletas</h2><p>Consulta documentos emitidos, productos y formas de pago.</p></div><button class="primary" type="button" data-open="punto-venta">+ Nueva boleta</button></header>
      <div class="boletas-filterbar">
        <label>Desde<input id="boletaDesde" type="date" value="${monthStart}"></label>
        <label>Hasta<input id="boletaHasta" type="date" value="${today.toISOString().slice(0,10)}"></label>
        <label>Local<select id="boletaLocal"><option value="">Todos</option><option value="01" ${currentLocal.local_codigo==="01"?"selected":""}>01 · Bodega</option><option value="02" ${currentLocal.local_codigo==="02"?"selected":""}>02 · Panaderia</option></select></label>
        <label>Folio<input id="boletaNumero" inputmode="numeric" placeholder="Numero"></label>
        <label>Vendedor<select id="boletaVendedor">${optionsHtml(catalogs.vendedores,"","Todos")}</select></label>
        <label>Forma de pago<select id="boletaPago">${optionsHtml(catalogs.formas_pago,"","Todas")}</select></label>
        <button id="boletaBuscar" class="primary" type="button">Buscar</button><button id="boletaLimpiar" class="ghost" type="button">Limpiar</button>
      </div>
      <div class="boletas-summary"><span id="boletaCount">Consultando...</span><strong id="boletaTotal">$0</strong></div>
      <div class="professional-table-wrap boletas-table-wrap"><table class="professional-table"><thead><tr><th>Folio</th><th>Fecha / Hora</th><th>Local</th><th>Caja</th><th>Cliente</th><th>Vendedor</th><th>Pago</th><th>Lineas</th><th>Total</th><th>Estado</th><th></th></tr></thead><tbody id="boletaRows"><tr><td colspan="11" class="empty">Cargando boletas...</td></tr></tbody></table></div>
    </section><div id="boletaDetail" class="editor-overlay" aria-hidden="true"></div>`;
  $("boletaBuscar").addEventListener("click", loadBoletas);
  $("boletaLimpiar").addEventListener("click",()=>{$("boletaNumero").value="";$("boletaVendedor").value="";$("boletaPago").value="";loadBoletas();});
  $("boletaRows").addEventListener("click",event=>{const button=event.target.closest("[data-boleta]");if(button)openBoletaDetail(button.dataset.local,button.dataset.boleta);});
  await loadBoletas();
}

async function loadBoletas() {
  const query=new URLSearchParams({fecha_desde:$("boletaDesde").value,fecha_hasta:$("boletaHasta").value,limit:"300"});
  if($("boletaLocal").value)query.set("local_codigo",$("boletaLocal").value);
  if($("boletaNumero").value)query.set("numero",$("boletaNumero").value);
  if($("boletaVendedor").value)query.set("vendedor_codigo",$("boletaVendedor").value);
  if($("boletaPago").value)query.set("fpago_codigo",$("boletaPago").value);
  const rows=await fetchJson(`/api/boletas?${query}`,[]);
  $("boletaCount").textContent=`${rows.length} boletas encontradas`;
  $("boletaTotal").textContent=posMoney(rows.reduce((sum,row)=>sum+Number(row.venta_totalventa||0),0));
  $("boletaRows").innerHTML=rows.length?rows.map(row=>{const anulada=["I","N","X"].includes(String(row.venta_estado||"").toUpperCase());return `<tr><td class="key-cell">${Number(row.venta_numero||0).toLocaleString("es-CL")}</td><td>${escapeHtml(row.venta_fecha||"")}<small>${escapeHtml(String(row.venta_hora||"").slice(11,19))}</small></td><td>${escapeHtml(row.local_codigo||"")}</td><td>${escapeHtml(row.caja_codigo??"-")}</td><td>${escapeHtml(row.cliente_nombre||"CONSUMIDOR FINAL")}<small>${escapeHtml(row.cliente_rut||"")}</small></td><td>${escapeHtml(row.vendedor_nombre||"-")}</td><td>${escapeHtml(row.formas_pago||row.venta_pago||"-")}</td><td>${Number(row.lineas||0)}</td><td><strong>${posMoney(row.venta_totalventa)}</strong></td><td><span class="status-badge ${anulada?"inactive":""}">${anulada?"Anulada":"Emitida"}</span></td><td><button class="row-edit" type="button" data-boleta="${row.venta_numero}" data-local="${escapeHtml(row.local_codigo)}">Ver detalle</button></td></tr>`;}).join(""):`<tr><td colspan="11" class="empty">No existen boletas para los filtros seleccionados.</td></tr>`;
}

async function openBoletaDetail(local,numero) {
  const data=await fetchJson(`/api/boletas/${encodeURIComponent(local)}/${encodeURIComponent(numero)}`,null);if(!data)return;
  const overlay=$("boletaDetail"),sale=data.venta;
  overlay.innerHTML=`<aside class="editor-drawer boleta-drawer"><header><div><p class="eyebrow">Boleta emitida</p><h3>Folio ${Number(sale.venta_numero).toLocaleString("es-CL")}</h3></div><button class="drawer-close" type="button">×</button></header>
    <div class="boleta-detail-meta"><span><small>Fecha</small><strong>${escapeHtml(sale.venta_fecha||"")}</strong></span><span><small>Local / Caja</small><strong>${escapeHtml(sale.local_codigo)} / ${escapeHtml(sale.caja_codigo??"-")}</strong></span><span><small>Cliente</small><strong>${escapeHtml(sale.cliente_nombre||"CONSUMIDOR FINAL")}</strong></span><span><small>Vendedor</small><strong>${escapeHtml(sale.vendedor_nombre||"-")}</strong></span></div>
    <div class="boleta-detail-scroll"><h4>Productos</h4><table class="detail-table"><thead><tr><th>Codigo</th><th>Producto</th><th>Cantidad</th><th>Neto</th><th>IVA</th><th>ILA</th><th>Total</th></tr></thead><tbody>${data.lineas.map(line=>`<tr><td>${escapeHtml(line.producto_codigo)}</td><td>${escapeHtml(line.producto_descripcion)}</td><td>${clNumber(line.venta_cantidad,3)}</td><td>${posMoney(line.venta_lineaneto)}</td><td>${posMoney(line.venta_lineaiva)}</td><td>${posMoney(line.venta_lineaila)}</td><td><strong>${posMoney(line.total_linea)}</strong></td></tr>`).join("")}</tbody></table>
    <h4>Formas de pago</h4><div class="boleta-payment-list">${data.pagos.length?data.pagos.map(pay=>`<p><span>${escapeHtml(pay.fpago_descripcion)}${pay.venta_numerodoc?` · ${escapeHtml(pay.venta_numerodoc)}`:""}</span><strong>${posMoney(pay.venta_pagomonto)}</strong></p>`).join(""):`<p><span>${escapeHtml(sale.venta_pago||"Sin detalle")}</span><strong>${posMoney(sale.venta_pagototal||sale.venta_totalventa)}</strong></p>`}</div></div>
    <footer><strong class="boleta-grand-total">Total ${posMoney(sale.venta_totalventa)}</strong><button class="primary drawer-close-footer" type="button">Cerrar</button></footer></aside>`;
  overlay.classList.add("open");overlay.setAttribute("aria-hidden","false");const close=()=>{overlay.classList.remove("open");overlay.setAttribute("aria-hidden","true");};overlay.querySelector(".drawer-close").onclick=close;overlay.querySelector(".drawer-close-footer").onclick=close;overlay.onclick=event=>{if(event.target===overlay)close();};
}

async function renderPos() {
  if (!currentLocal || !currentCaja || !currentUser) return;
  const [catalogs,cashStatus] = await Promise.all([fetchJson(`/api/pos/catalogos?local_codigo=${encodeURIComponent(currentLocal.local_codigo)}`, {
    formas_pago: [], vendedores: [], lista_codigo: currentLocal.local_codigo === "02" ? "30" : "01",
    folio_siguiente: 0, bodega_codigo: currentLocal.local_bodega || currentLocal.local_codigo
  }),fetchJson(`/api/pos/caja/status?local_codigo=${encodeURIComponent(currentLocal.local_codigo)}&caja_codigo=${encodeURIComponent(currentCaja.caja_codigo)}`,{cerrada:false,pagos:[],total:0,documentos:0})]);
  posState = {lines: [], payments: [{fpago_codigo:"01", monto:0, numero_documento:""}], catalogs, cashStatus};
  $("content").innerHTML = `
    <section class="pos-shell">
      <header class="pos-header">
        <div><p class="eyebrow">Ventas / Boleta</p><h2>Punto de venta rapido</h2></div>
        <div class="pos-session">
          <span><small>Local</small><strong>${escapeHtml(currentLocal.local_codigo)} · ${escapeHtml(currentLocal.local_descripcion || "Local")}</strong></span>
          <span><small>Caja</small><strong>${escapeHtml(currentCaja.caja_codigo)}</strong></span>
          <span><small>Folio</small><strong id="posFolio">${Number(catalogs.folio_siguiente || 0).toLocaleString("es-CL")}</strong></span>
          <span><small>Usuario</small><strong>${escapeHtml(currentUser.user_name || "Usuario")}</strong></span>
          <span><small>Fecha y hora</small><strong data-live-datetime>${new Date().toLocaleString("es-CL")}</strong></span>
        </div>
      </header>
      <div class="pos-tools">
        <label class="pos-scanner"><span>Escanear codigo de barras o QR</span><input id="posScanner" autocomplete="off" placeholder="Escanee o ingrese codigo y presione Enter"><b>SCAN</b></label>
        <label>Cliente / RUT<input id="posClient" value="0" placeholder="Consumidor final"></label>
        <label>Vendedor<select id="posSeller">${optionsHtml(catalogs.vendedores, currentUser.vendedor_codigo || "", "Seleccione")}</select></label>
        <label>Bodega<input value="${escapeHtml(catalogs.bodega_codigo || "")}" readonly></label>
        <button id="posSearchButton" class="ghost" type="button">Buscar producto</button>
      </div>
      <div id="posSearchResults" class="pos-search-results"></div>
      <div class="pos-workspace">
        <section class="pos-cart-panel"><div class="pos-table-wrap"><table class="pos-table"><thead><tr><th>Codigo</th><th>Producto</th><th>Cantidad</th><th>Precio</th><th>Descuento</th><th>Total</th><th></th></tr></thead><tbody id="posLines"></tbody></table></div><div id="posEmpty" class="pos-empty"><strong>Venta lista para comenzar</strong><span>Escanee un producto o use la busqueda.</span></div></section>
        <aside class="pos-checkout">
          <div class="pos-total-head"><span>Resumen de boleta</span><small>Lista ${escapeHtml(catalogs.lista_codigo)}</small></div>
          <div class="pos-totals"><p><span>Neto</span><b id="posNet">$0</b></p><p><span>IVA</span><b id="posVat">$0</b></p><p><span>ILA</span><b id="posIla">$0</b></p><p><span>Descuento digitado</span><b id="posDiscount">$0</b></p><div><span>TOTAL BOLETA</span><strong id="posTotal">$0</strong></div><label class="pos-payable"><span>Total a pagar</span><input id="posPayable" value="$0" readonly><small id="posRounding">Sin redondeo</small></label></div>
          <div class="pos-payments"><header><strong>Formas de pago</strong><button id="posAddPayment" type="button">+ Agregar</button></header><div id="posPaymentRows"></div><div class="pos-balance"><span>Pagado <b id="posPaid">$0</b></span><span>Falta <b id="posPending">$0</b></span></div><div class="pos-change"><span>Vuelto</span><strong id="posChange">$0</strong></div></div>
          <p id="posMessage" class="pos-message">${cashStatus.cerrada?'Caja cerrada para hoy. No se pueden emitir nuevas boletas.':''}</p><button id="posEmit" class="primary pos-emit" type="button" ${cashStatus.cerrada?'disabled':''}>Emitir boleta</button><div class="pos-secondary-actions"><button id="posCloseCash" class="ghost" type="button">${cashStatus.cerrada?'Ver cierre de caja':'Cierre de caja'}</button><button id="posClear" class="ghost" type="button">Cancelar venta</button></div>
        </aside>
      </div>
      <footer class="pos-shortcuts"><span><b>F2</b> Producto</span><span><b>F4</b> Cliente</span><span><b>F6</b> Pago</span><span><b>F10</b> Emitir</span><em>Scanner conectado</em></footer>
    </section>`;
  $("posScanner").addEventListener("keydown", event => { if (event.key === "Enter") { event.preventDefault(); posScan(); } });
  $("posSearchButton").addEventListener("click", posSearch);
  $("posAddPayment").addEventListener("click", () => { posState.payments.push({fpago_codigo:"01",monto:0,numero_documento:""}); renderPosPayments(); });
  $("posClear").addEventListener("click", resetPos); $("posEmit").addEventListener("click", emitPosSale);
  $("posCloseCash").addEventListener("click", openCashClose);
  $("posLines").addEventListener("input", posLineEvent); $("posLines").addEventListener("click", posLineEvent);
  $("posPaymentRows").addEventListener("input", posPaymentEvent); $("posPaymentRows").addEventListener("change", posPaymentEvent); $("posPaymentRows").addEventListener("click", posPaymentEvent);
  $("posSearchResults").addEventListener("click", async event => { const button=event.target.closest("[data-pos-product]"); if(!button)return; $("posScanner").value=button.dataset.posProduct; $("posSearchResults").innerHTML=""; await posScan(); });
  posKeyHandler = event => { if(event.key==="F2"){event.preventDefault();$("posScanner")?.focus();} if(event.key==="F4"){event.preventDefault();$("posClient")?.focus();} if(event.key==="F6"){event.preventDefault();document.querySelector(".pos-payment-amount")?.focus();} if(event.key==="F10"){event.preventDefault();emitPosSale();} };
  document.addEventListener("keydown", posKeyHandler); renderPosLines(); renderPosPayments(); $("posScanner").focus();
}

function posMoney(value) { return Number(Math.round(value || 0)).toLocaleString("es-CL", {style:"currency",currency:"CLP",maximumFractionDigits:0}); }
function posTotals() { const t={net:0,vat:0,ila:0,gross:0,discount:0}; for(const l of posState.lines){const q=Number(l.cantidad||0),d=Number(l.descuento||0)/100;t.net+=Math.round(Number(l.precio_neto||0)*q*(1-d));t.vat+=Math.round(Number(l.precio_iva||0)*q*(1-d));t.ila+=Math.round(Number(l.precio_ila||0)*q*(1-d));t.gross+=Math.round(Number(l.precio_venta||0)*q*(1-d));t.discount+=Math.round(Number(l.precio_venta||0)*q*d);} return t; }
async function posScan(){const input=$("posScanner"),code=input.value.trim();if(!code)return;const response=await fetch(`/api/pos/producto?codigo=${encodeURIComponent(code)}&local_codigo=${encodeURIComponent(currentLocal.local_codigo)}`);if(!response.ok){$("posMessage").textContent="Producto o codigo de barra no encontrado.";input.select();return;}const p=await response.json(),existing=posState.lines.find(l=>l.producto_codigo===p.producto_codigo);if(existing)existing.cantidad=Number(existing.cantidad)+Number(p.cantidad||1);else posState.lines.push({...p,cantidad:Number(p.cantidad||1),descuento:0});input.value="";$("posMessage").textContent=`${p.producto_descripcion} agregado`;renderPosLines();input.focus();}
async function posSearch(){const q=$("posScanner").value.trim();if(!q){$("posScanner").focus();return;}const rows=await fetchJson(`/api/pos/productos?q=${encodeURIComponent(q)}&local_codigo=${encodeURIComponent(currentLocal.local_codigo)}`,[]);$("posSearchResults").innerHTML=rows.length?rows.map(r=>`<button type="button" data-pos-product="${escapeHtml(r.producto_codigo)}"><b>${escapeHtml(r.producto_codigo)}</b><span>${escapeHtml(r.producto_descripcion)}</span><strong>${posMoney(r.precio_venta)}</strong></button>`).join(""):`<span>Sin coincidencias.</span>`;}
function renderPosLines(){const body=$("posLines");body.innerHTML=posState.lines.map((l,i)=>`<tr><td><b>${escapeHtml(l.producto_codigo)}</b><small>${escapeHtml(l.unidad_codigo||"UN")}</small></td><td>${escapeHtml(l.producto_descripcion||"")}</td><td><div class="qty-control"><button data-pos-step="-1" data-index="${i}">-</button><input data-pos-qty data-index="${i}" type="number" min="0.001" step="${l.unidad_codigo==="KG"?"0.001":"1"}" value="${l.cantidad}"><button data-pos-step="1" data-index="${i}">+</button></div></td><td>${posMoney(l.precio_venta)}</td><td><input class="pos-discount" data-pos-discount data-index="${i}" type="number" inputmode="decimal" min="0" max="100" step="0.1" value="${l.descuento||0}">%</td><td><strong>${posMoney(Number(l.precio_venta||0)*Number(l.cantidad||0)*(1-Number(l.descuento||0)/100))}</strong></td><td><button class="pos-remove" data-pos-remove data-index="${i}" title="Quitar">×</button></td></tr>`).join("");$("posEmpty").style.display=posState.lines.length?"none":"grid";updatePosSummary();}
function posLineEvent(event){const i=Number(event.target.dataset.index);if(!Number.isInteger(i)||!posState.lines[i])return;if(event.target.matches("[data-pos-step]"))posState.lines[i].cantidad=Math.max(.001,Number(posState.lines[i].cantidad)+Number(event.target.dataset.posStep));if(event.target.matches("[data-pos-qty]"))posState.lines[i].cantidad=Math.max(.001,Number(event.target.value||0));if(event.target.matches("[data-pos-discount]"))posState.lines[i].descuento=Math.max(0,Math.min(100,Number(event.target.value||0)));if(event.target.matches("[data-pos-remove]"))posState.lines.splice(i,1);renderPosLines();}
function renderPosPayments(){$("posPaymentRows").innerHTML=posState.payments.map((p,i)=>`<div class="pos-payment-row"><select data-pay-code data-index="${i}">${optionsHtml(posState.catalogs.formas_pago,p.fpago_codigo,"Forma de pago")}</select><input class="pos-payment-amount" data-pay-amount data-index="${i}" type="number" inputmode="numeric" min="0" step="1" value="${p.monto||0}" placeholder="Monto recibido"><input data-pay-doc data-index="${i}" value="${escapeHtml(p.numero_documento||"")}" placeholder="Nro. documento"><button data-pay-remove data-index="${i}" type="button">×</button></div>`).join("");updatePosSummary();}
function posPaymentEvent(event){const i=Number(event.target.dataset.index);if(!Number.isInteger(i)||!posState.payments[i])return;if(event.target.matches("[data-pay-code]"))posState.payments[i].fpago_codigo=event.target.value;if(event.target.matches("[data-pay-amount]"))posState.payments[i].monto=Math.max(0,Number(event.target.value||0));if(event.target.matches("[data-pay-doc]"))posState.payments[i].numero_documento=event.target.value;if(event.target.matches("[data-pay-remove]")){posState.payments.splice(i,1);renderPosPayments();return;}updatePosSummary();}
function chileRound(value){const amount=Math.round(Number(value||0)),rest=((amount%10)+10)%10;return rest<=5?amount-rest:amount+(10-rest);}
function updatePosSummary(){if(!posState||!$("posTotal"))return;const t=posTotals(),paid=posState.payments.reduce((s,p)=>s+Number(p.monto||0),0),hasCash=posState.payments.some(p=>p.fpago_codigo==="01"),nonCash=posState.payments.filter(p=>p.fpago_codigo!=="01").reduce((s,p)=>s+Number(p.monto||0),0),cashExact=Math.max(0,t.gross-nonCash),payable=nonCash+(hasCash?chileRound(cashExact):cashExact),pending=Math.max(0,payable-paid),change=Math.max(0,paid-payable);$("posNet").textContent=posMoney(t.net);$("posVat").textContent=posMoney(t.vat);$("posIla").textContent=posMoney(t.ila);$("posDiscount").textContent=posMoney(t.discount);$("posTotal").textContent=posMoney(t.gross);$("posPayable").value=posMoney(payable);$("posRounding").textContent=hasCash&&payable!==t.gross?`Redondeo efectivo: ${payable-t.gross>0?'+':''}${posMoney(payable-t.gross)}`:'Sin redondeo';$("posPaid").textContent=posMoney(paid);$("posPending").textContent=posMoney(pending);$("posChange").textContent=posMoney(change);}
async function openCashClose(){const status=await fetchJson(`/api/pos/caja/status?local_codigo=${encodeURIComponent(currentLocal.local_codigo)}&caja_codigo=${encodeURIComponent(currentCaja.caja_codigo)}`,null);if(!status)return;const existing=$("cashCloseOverlay");if(existing)existing.remove();const overlay=document.createElement('div');overlay.id='cashCloseOverlay';overlay.className='editor-overlay open';overlay.innerHTML=`<aside class="editor-drawer cash-close-drawer"><header><div><p class="eyebrow">Caja ${escapeHtml(currentCaja.caja_codigo)}</p><h3>${status.cerrada?'Cierre registrado':'Cierre de caja'}</h3></div><button class="drawer-close" type="button">×</button></header><div class="cash-close-summary"><article><span>Boletas</span><strong>${Number(status.documentos||0)}</strong></article><article><span>Venta total</span><strong>${posMoney(status.total)}</strong></article>${status.pagos.map(pay=>`<article><span>${escapeHtml(pay.descripcion)}</span><strong>${posMoney(pay.monto)}</strong></article>`).join('')}</div><div class="cash-close-form"><label>Efectivo contado<input id="cashCounted" type="number" inputmode="numeric" value="${status.cierre?.efectivo_contado||0}" ${status.cerrada?'readonly':''}></label><label>Observacion<textarea id="cashObservation" ${status.cerrada?'readonly':''}>${escapeHtml(status.cierre?.observacion||'')}</textarea></label><p id="cashCloseMessage"></p></div><footer><button class="ghost drawer-close-footer" type="button">Cerrar</button>${status.cerrada?'':`<button id="confirmCashClose" class="primary" type="button">Confirmar cierre</button>`}</footer></aside>`;document.body.appendChild(overlay);const close=()=>overlay.remove();overlay.querySelector('.drawer-close').onclick=close;overlay.querySelector('.drawer-close-footer').onclick=close;if(!status.cerrada)overlay.querySelector('#confirmCashClose').onclick=async()=>{const button=overlay.querySelector('#confirmCashClose');button.disabled=true;try{const result=await saveJson('/api/pos/caja/cerrar','POST',{local_codigo:currentLocal.local_codigo,caja_codigo:Number(currentCaja.caja_codigo),user_id:Number(currentUser.user_id),efectivo_contado:Number(overlay.querySelector('#cashCounted').value||0),observacion:overlay.querySelector('#cashObservation').value});overlay.querySelector('#cashCloseMessage').textContent=`Caja cerrada. Diferencia ${posMoney(result.diferencia)}.`;$("posEmit").disabled=true;$("posCloseCash").textContent='Ver cierre de caja';}catch(error){overlay.querySelector('#cashCloseMessage').textContent=error.message;}finally{button.disabled=false;}};}
function resetPos(){posState.lines=[];posState.payments=[{fpago_codigo:"01",monto:0,numero_documento:""}];$("posMessage").textContent="Venta cancelada.";renderPosLines();renderPosPayments();$("posScanner").focus();}
async function emitPosSale(){const button=$("posEmit"),message=$("posMessage");if(!posState.lines.length){message.textContent="Agregue productos a la venta.";return;}button.disabled=true;message.textContent="Guardando boleta...";try{const data=await saveJson("/api/pos/boletas","POST",{local_codigo:currentLocal.local_codigo,caja_codigo:Number(currentCaja.caja_codigo),user_id:Number(currentUser.user_id),vendedor_codigo:$("posSeller").value||currentUser.vendedor_codigo||"",cliente_rut:$("posClient").value||"0",lines:posState.lines.map(l=>({producto_codigo:l.producto_codigo,cantidad:Number(l.cantidad),descuento:Number(l.descuento||0)})),payments:posState.payments});message.textContent=`Boleta ${Number(data.folio).toLocaleString("es-CL")} emitida. Vuelto ${posMoney(data.vuelto)}.`;posState.catalogs.folio_siguiente=Number(data.folio)+1;$("posFolio").textContent=Number(data.folio+1).toLocaleString("es-CL");posState.lines=[];posState.payments=[{fpago_codigo:"01",monto:0,numero_documento:""}];renderPosLines();renderPosPayments();$("posScanner").focus();}catch(error){message.textContent=error.message;}finally{button.disabled=false;}}

function renderPicking() {
  $("content").innerHTML = `
    <section class="filters-card">
      <div class="section-head">
        <div>
          <p class="eyebrow">Ventas / Reporte</p>
          <h2>Picking Comercial</h2>
          <p>Genera picking consolidado por producto desde facturas y boletas emitidas.</p>
        </div>
      </div>
      <div class="filters">
        <label>Fecha<input id="fecha" type="date"></label>
        <label>Ruta<select id="ruta"><option value="">Todas</option></select></label>
        <label>Vendedor<select id="vendedor"><option value="">Todos</option></select></label>
        <button id="buscar" type="button" class="ghost">Vista previa</button>
        <button id="pdf" type="button" class="primary">Generar PDF</button>
      </div>
    </section>

    <section class="summary">
      <article class="card"><span>Productos</span><strong id="productos">0</strong><small>Lineas consolidadas</small></article>
      <article class="card"><span>Kilos</span><strong id="kilos">0,00</strong><small>Total preparado</small></article>
      <article class="card"><span>Unidades</span><strong id="unidades">0,00</strong><small>Unidades vendidas</small></article>
    </section>

    <section class="table-wrap">
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Codigo</th>
              <th>Descripcion</th>
              <th>Cajas</th>
              <th>Resto</th>
              <th>Kilos</th>
              <th>Un. vendidas</th>
            </tr>
          </thead>
          <tbody id="rows">
            <tr><td colspan="6" class="empty">Seleccione filtros y presione Vista previa.</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  `;

  $("buscar").addEventListener("click", preview);
  $("pdf").addEventListener("click", openPdf);
}

function params() {
  const query = new URLSearchParams();
  query.set("fecha", $("fecha").value);
  if ($("ruta").value) query.set("ruta_id", $("ruta").value);
  if ($("vendedor").value) query.set("vendedor_codigo", $("vendedor").value);
  return query;
}

async function loadCombos() {
  const today = new Date();
  $("fecha").value = today.toISOString().slice(0, 10);

  const [rutas, vendedores] = await Promise.all([
    fetch("/api/rutas").then(r => r.json()).catch(() => []),
    fetch("/api/vendedores").then(r => r.json()).catch(() => [])
  ]);

  for (const r of rutas) {
    const opt = document.createElement("option");
    opt.value = r.ruta_id;
    opt.textContent = r.ruta_nombre || `Ruta ${r.ruta_id}`;
    $("ruta").appendChild(opt);
  }

  for (const v of vendedores) {
    const opt = document.createElement("option");
    opt.value = v.vendedor_codigo;
    opt.textContent = v.vendedor_nombre || v.vendedor_codigo;
    $("vendedor").appendChild(opt);
  }
}

async function preview() {
  if (!$("fecha").value) {
    alert("Seleccione fecha");
    return;
  }
  const res = await fetch(`/api/picking/resumen?${params().toString()}`);
  if (!res.ok) {
    alert(await res.text());
    return;
  }
  const data = await res.json();
  $("productos").textContent = data.productos || 0;
  $("kilos").textContent = clNumber(data.total_kilos);
  $("unidades").textContent = clNumber(data.total_unidades);

  const tbody = $("rows");
  tbody.innerHTML = "";
  if (!data.rows || data.rows.length === 0) {
    tbody.innerHTML = `<tr><td colspan="6" class="empty">No hay productos para los filtros seleccionados.</td></tr>`;
    return;
  }

  for (const row of data.rows) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${escapeHtml(row.producto_codigo || "")}</td>
      <td>${escapeHtml(row.descripcion || "")}</td>
      <td>${clNumber(row.cajas, 3)}</td>
      <td>${clNumber(row.resto)}</td>
      <td>${clNumber(row.kilos)}</td>
      <td>${clNumber(row.unidades)}</td>
    `;
    tbody.appendChild(tr);
  }
}

function openPdf() {
  if (!$("fecha").value) {
    alert("Seleccione fecha");
    return;
  }
  window.open(`/api/picking/pdf?${params().toString()}`, "_blank");
}

function enterApp(user, local, caja, persist = true) {
  currentUser = user;
  currentLocal = local;
  currentCaja = caja;
  $("activeUser").textContent = user?.user_name || "Usuario";
  $("activeLocal").textContent = `${local?.local_codigo || ""} · ${local?.local_descripcion || "Local"}`;
  $("activeCaja").textContent = `Caja ${caja?.caja_codigo || "-"}`;
  $("loginView").classList.add("is-hidden");
  $("appView").classList.remove("is-hidden");
  setToday();
  if (clockTimer) clearInterval(clockTimer);
  const updateClock = () => {
    const now = new Date();
    $("activeTime").textContent = now.toLocaleTimeString("es-CL", {hour:"2-digit", minute:"2-digit", second:"2-digit"});
    document.querySelectorAll("[data-live-datetime]").forEach(node => node.textContent = now.toLocaleString("es-CL"));
  };
  updateClock();
  clockTimer = setInterval(updateClock, 1000);
  if (persist) storeSession($("rememberSession")?.checked !== false);
  startSessionControl();
  renderMenu();
  navigate("home");
}

function logoutApp(message = "") {
  currentUser = null;
  currentLocal = null;
  currentCaja = null;
  clearStoredSession();
  stopSessionControl();
  if (clockTimer) clearInterval(clockTimer);
  clockTimer = null;
  $("activeLocal").textContent = "Sin local";
  $("activeCaja").textContent = "Sin caja";
  $("loginPass").value = "";
  $("appView").classList.add("is-hidden");
  $("loginView").classList.remove("is-hidden");
  loadLoginUsers().then(() => {
    if (message) setLoginMessage(message, "info");
  });
}

async function submitLogin(event) {
  event.preventDefault();
  const userId = Number($("loginUser").value);
  const localCodigo = $("loginLocal").value;
  const cajaCodigo = Number($("loginCaja").value);
  const password = $("loginPass").value;
  if (!userId) {
    setLoginMessage("Seleccione un usuario.", "error");
    return;
  }
  if (!password) {
    setLoginMessage("Ingrese la contrasena.", "error");
    return;
  }
  if (!localCodigo) {
    setLoginMessage("Seleccione un local.", "error");
    return;
  }
  if (!cajaCodigo) {
    setLoginMessage("Seleccione una caja.", "error");
    return;
  }

  setLoginMessage("Validando credenciales...", "info");
  const res = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ user_id: userId, password, local_codigo: localCodigo, caja_codigo: cajaCodigo })
  });
  if (!res.ok) {
    const message = await res.text();
    setLoginMessage(message.replaceAll('"', ""), "error");
    return;
  }

  const data = await res.json();
  setLoginMessage("Acceso autorizado.", "ok");
  enterApp(data.user, data.local, data.caja, true);
}

$("loginForm").addEventListener("submit", submitLogin);

$("logout").addEventListener("click", () => logoutApp());

$("menuToggle").addEventListener("click", () => {
  document.querySelector(".sidebar").classList.toggle("open");
});

$("content").addEventListener("click", (event) => {
  const button = event.target.closest("[data-open]");
  if (!button) return;
  navigate(button.dataset.open);
});

for (const eventName of ["pointerdown", "keydown", "scroll", "touchstart"]) {
  document.addEventListener(eventName, touchSession, { passive: true });
}
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) touchSession();
});

if (!restoreSession()) loadLoginUsers();
