const state = {
    products: [],
    categories: [],
    cart: JSON.parse(localStorage.getItem("techkart-cart") || "[]"),
    search: "",
    category: "All",
    activeView: "store",
    adminPin: sessionStorage.getItem("techkart-admin-pin") || "",
    orders: [],
    lowStock: []
};

const els = {
    summaryGrid: document.querySelector("#summaryGrid"),
    productGrid: document.querySelector("#productGrid"),
    categoryStrip: document.querySelector("#categoryStrip"),
    searchInput: document.querySelector("#searchInput"),
    cartList: document.querySelector("#cartList"),
    cartTotal: document.querySelector("#cartTotal"),
    checkoutButton: document.querySelector("#checkoutButton"),
    clearCartButton: document.querySelector("#clearCartButton"),
    refreshButton: document.querySelector("#refreshButton"),
    pinInput: document.querySelector("#pinInput"),
    productForm: document.querySelector("#productForm"),
    productId: document.querySelector("#productId"),
    productName: document.querySelector("#productName"),
    productCategory: document.querySelector("#productCategory"),
    productPrice: document.querySelector("#productPrice"),
    productStock: document.querySelector("#productStock"),
    saveProductButton: document.querySelector("#saveProductButton"),
    cancelEditButton: document.querySelector("#cancelEditButton"),
    inventoryTable: document.querySelector("#inventoryTable"),
    lowStockLimit: document.querySelector("#lowStockLimit"),
    lowStockList: document.querySelector("#lowStockList"),
    loadOrdersButton: document.querySelector("#loadOrdersButton"),
    ordersList: document.querySelector("#ordersList"),
    toast: document.querySelector("#toast")
};

const money = new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0
});

function formatMoney(value) {
    return money.format(value || 0);
}

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});

    if (state.adminPin) {
        headers.set("X-Admin-Pin", state.adminPin);
    }

    const response = await fetch(path, { ...options, headers });
    const text = await response.text();
    const data = text ? JSON.parse(text) : {};

    if (!response.ok) {
        throw new Error(data.error || "Request failed.");
    }

    return data;
}

function saveCart() {
    localStorage.setItem("techkart-cart", JSON.stringify(state.cart));
}

function showToast(message) {
    els.toast.textContent = message;
    els.toast.classList.add("show");
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => els.toast.classList.remove("show"), 2600);
}

async function loadStore() {
    const [productsData, categoriesData, summaryData] = await Promise.all([
        api("/api/products"),
        api("/api/categories"),
        api("/api/summary")
    ]);

    state.products = productsData.products;
    state.categories = categoriesData.categories;
    reconcileCart();
    renderSummary(summaryData);
    renderCategories();
    renderProducts();
    renderCart();
    renderInventory();
}

function renderSummary(summary) {
    const cards = [
        ["Products", summary.productCount],
        ["Categories", summary.categoryCount],
        ["Units", summary.totalStock],
        ["Low Stock", summary.lowStockCount],
        ["Value", formatMoney(summary.inventoryValue)]
    ];

    els.summaryGrid.innerHTML = cards.map(([label, value]) => `
        <article class="summary-card">
            <span>${label}</span>
            <strong>${value}</strong>
        </article>
    `).join("");
}

function renderCategories() {
    const categories = ["All", ...state.categories];

    els.categoryStrip.innerHTML = categories.map(category => `
        <button class="category-button ${category === state.category ? "active" : ""}" type="button" data-category="${escapeHtml(category)}">
            ${escapeHtml(category)}
        </button>
    `).join("");
}

function renderProducts() {
    const visibleProducts = state.products.filter(product => {
        const search = state.search.toLowerCase();
        const matchesSearch = !search
            || product.name.toLowerCase().includes(search)
            || product.category.toLowerCase().includes(search);
        const matchesCategory = state.category === "All" || product.category === state.category;

        return matchesSearch && matchesCategory;
    });

    if (visibleProducts.length === 0) {
        els.productGrid.innerHTML = `<div class="empty-state">No products found.</div>`;
        return;
    }

    els.productGrid.innerHTML = visibleProducts.map(product => {
        const inCart = getCartItem(product.id)?.quantity || 0;
        const remaining = product.stock - inCart;
        const stockClass = product.stock === 0 ? "empty" : product.stock <= 5 ? "low" : "";
        const disabled = remaining <= 0 ? "disabled" : "";

        return `
            <article class="product-card">
                <div class="product-media" data-category="${escapeHtml(product.category)}">${categoryMark(product.category)}</div>
                <div class="product-info">
                    <h3>${escapeHtml(product.name)}</h3>
                    <div class="product-meta">
                        <span>${escapeHtml(product.category)}</span>
                        <span>#${product.id}</span>
                    </div>
                    <div class="product-price">${formatMoney(product.price)}</div>
                    <span class="stock-pill ${stockClass}">${product.stock} in stock</span>
                </div>
                <div class="product-actions">
                    <button class="primary-button wide" type="button" data-add="${product.id}" ${disabled}>Add to Cart</button>
                </div>
            </article>
        `;
    }).join("");
}

function renderCart() {
    const items = state.cart
        .map(item => ({ ...item, product: state.products.find(product => product.id === item.productId) }))
        .filter(item => item.product);

    if (items.length === 0) {
        els.cartList.innerHTML = `<div class="empty-state">Cart is empty.</div>`;
        els.cartTotal.textContent = formatMoney(0);
        els.checkoutButton.disabled = true;
        return;
    }

    let total = 0;

    els.cartList.innerHTML = items.map(item => {
        const lineTotal = item.product.price * item.quantity;
        total += lineTotal;

        return `
            <article class="cart-item">
                <div>
                    <h3>${escapeHtml(item.product.name)}</h3>
                    <div class="muted">${formatMoney(item.product.price)} each</div>
                    <button class="small-button" type="button" data-remove="${item.productId}">Remove</button>
                </div>
                <div class="qty-control">
                    <button type="button" data-dec="${item.productId}" aria-label="Decrease quantity">-</button>
                    <span>${item.quantity}</span>
                    <button type="button" data-inc="${item.productId}" aria-label="Increase quantity">+</button>
                </div>
            </article>
        `;
    }).join("");

    els.cartTotal.textContent = formatMoney(total);
    els.checkoutButton.disabled = false;
}

function renderInventory() {
    if (state.products.length === 0) {
        els.inventoryTable.innerHTML = `<tr><td colspan="5">No products found.</td></tr>`;
        return;
    }

    els.inventoryTable.innerHTML = state.products.map(product => `
        <tr>
            <td><strong>${escapeHtml(product.name)}</strong><div class="muted">#${product.id}</div></td>
            <td>${escapeHtml(product.category)}</td>
            <td>${formatMoney(product.price)}</td>
            <td>${product.stock}</td>
            <td>
                <div class="table-actions">
                    <button class="small-button" type="button" data-edit="${product.id}">Edit</button>
                    <button class="danger-button" type="button" data-delete="${product.id}">Delete</button>
                </div>
            </td>
        </tr>
    `).join("");
}

function renderLowStock() {
    if (!state.lowStock.length) {
        els.lowStockList.innerHTML = `<div class="empty-state">No low stock items.</div>`;
        return;
    }

    els.lowStockList.innerHTML = state.lowStock.map(product => `
        <article class="mini-item">
            <h3>${escapeHtml(product.name)}</h3>
            <div class="muted">${escapeHtml(product.category)} | ${product.stock} left</div>
        </article>
    `).join("");
}

function renderOrders() {
    if (!state.orders.length) {
        els.ordersList.innerHTML = `<div class="empty-state">No orders loaded.</div>`;
        return;
    }

    els.ordersList.innerHTML = state.orders.map(order => `
        <article class="order-item">
            <h3>Order #${order.orderId}</h3>
            <div class="muted">${escapeHtml(order.dateTime)} | ${escapeHtml(order.status)}</div>
            <p>${escapeHtml(order.details)}</p>
            <strong>${formatMoney(order.totalAmount)}</strong>
        </article>
    `).join("");
}

function setView(view) {
    state.activeView = view;
    document.querySelectorAll(".view").forEach(section => section.classList.toggle("active", section.id === `${view}View`));
    document.querySelectorAll(".tab-button").forEach(button => button.classList.toggle("active", button.dataset.view === view));

    if (view === "admin") {
        loadAdminReports();
    }
}

function addToCart(productId) {
    const product = state.products.find(item => item.id === productId);

    if (!product || product.stock <= 0) {
        showToast("This product is out of stock.");
        return;
    }

    const cartItem = getCartItem(productId);

    if (cartItem) {
        if (cartItem.quantity >= product.stock) {
            showToast("No more stock available.");
            return;
        }

        cartItem.quantity++;
    } else {
        state.cart.push({ productId, quantity: 1 });
    }

    saveCart();
    renderProducts();
    renderCart();
}

function changeQuantity(productId, delta) {
    const product = state.products.find(item => item.id === productId);
    const cartItem = getCartItem(productId);

    if (!product || !cartItem) {
        return;
    }

    cartItem.quantity += delta;

    if (cartItem.quantity <= 0) {
        state.cart = state.cart.filter(item => item.productId !== productId);
    } else if (cartItem.quantity > product.stock) {
        cartItem.quantity = product.stock;
        showToast("Quantity adjusted to available stock.");
    }

    saveCart();
    renderProducts();
    renderCart();
}

function removeFromCart(productId) {
    state.cart = state.cart.filter(item => item.productId !== productId);
    saveCart();
    renderProducts();
    renderCart();
}

function getCartItem(productId) {
    return state.cart.find(item => item.productId === productId);
}

function reconcileCart() {
    state.cart = state.cart
        .map(item => {
            const product = state.products.find(product => product.id === item.productId);

            if (!product) {
                return null;
            }

            return {
                productId: item.productId,
                quantity: Math.min(item.quantity, product.stock)
            };
        })
        .filter(item => item && item.quantity > 0);

    saveCart();
}

async function checkout() {
    if (!state.cart.length) {
        return;
    }

    const body = new URLSearchParams({
        items: state.cart.map(item => `${item.productId}:${item.quantity}`).join(",")
    });

    try {
        const result = await api("/api/orders", { method: "POST", body });
        state.cart = [];
        saveCart();
        await loadStore();
        showToast(`Order #${result.order.orderId} placed.`);
    } catch (error) {
        showToast(error.message);
        await loadStore();
    }
}

async function saveProduct(event) {
    event.preventDefault();
    rememberPin();

    const body = new URLSearchParams({
        name: els.productName.value,
        category: els.productCategory.value,
        price: els.productPrice.value,
        stock: els.productStock.value
    });

    const editingId = els.productId.value;
    const path = editingId ? `/api/products/${editingId}` : "/api/products";
    const method = editingId ? "PUT" : "POST";

    try {
        await api(path, { method, body });
        resetProductForm();
        await loadStore();
        await loadAdminReports();
        showToast(editingId ? "Product updated." : "Product added.");
    } catch (error) {
        showToast(error.message);
    }
}

function editProduct(productId) {
    const product = state.products.find(item => item.id === productId);

    if (!product) {
        return;
    }

    els.productId.value = product.id;
    els.productName.value = product.name;
    els.productCategory.value = product.category;
    els.productPrice.value = product.price;
    els.productStock.value = product.stock;
    els.saveProductButton.textContent = "Update Product";
}

async function deleteProduct(productId) {
    rememberPin();

    try {
        await api(`/api/products/${productId}`, { method: "DELETE" });
        state.cart = state.cart.filter(item => item.productId !== productId);
        saveCart();
        await loadStore();
        await loadAdminReports();
        showToast("Product removed.");
    } catch (error) {
        showToast(error.message);
    }
}

function resetProductForm() {
    els.productForm.reset();
    els.productId.value = "";
    els.saveProductButton.textContent = "Add Product";
}

function rememberPin() {
    state.adminPin = els.pinInput.value.trim();
    sessionStorage.setItem("techkart-admin-pin", state.adminPin);
}

async function loadAdminReports() {
    rememberPin();

    if (!state.adminPin) {
        state.lowStock = [];
        state.orders = [];
        renderLowStock();
        renderOrders();
        return;
    }

    try {
        const limit = els.lowStockLimit.value || "5";
        const [lowStockData, ordersData] = await Promise.all([
            api(`/api/reports/low-stock?limit=${encodeURIComponent(limit)}`),
            api("/api/orders")
        ]);

        state.lowStock = lowStockData.products;
        state.orders = ordersData.orders;
        renderLowStock();
        renderOrders();
    } catch (error) {
        state.lowStock = [];
        state.orders = [];
        renderLowStock();
        renderOrders();
        showToast(error.message);
    }
}

function categoryMark(category) {
    const normalized = category.toLowerCase();

    if (normalized.includes("cpu")) return "CPU";
    if (normalized.includes("gpu")) return "GPU";
    if (normalized.includes("ram")) return "RAM";
    if (normalized.includes("storage") || normalized.includes("ssd")) return "SSD";
    if (normalized.includes("mother")) return "MB";
    if (normalized.includes("psu") || normalized.includes("power")) return "PSU";
    return category.slice(0, 3).toUpperCase();
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#039;");
}

document.querySelectorAll(".tab-button").forEach(button => {
    button.addEventListener("click", () => setView(button.dataset.view));
});

els.searchInput.addEventListener("input", () => {
    state.search = els.searchInput.value.trim();
    window.clearTimeout(els.searchInput.timer);
    els.searchInput.timer = window.setTimeout(renderProducts, 120);
});

els.categoryStrip.addEventListener("click", event => {
    const button = event.target.closest("[data-category]");

    if (!button) {
        return;
    }

    state.category = button.dataset.category;
    renderCategories();
    renderProducts();
});

els.productGrid.addEventListener("click", event => {
    const addButton = event.target.closest("[data-add]");

    if (addButton) {
        addToCart(Number(addButton.dataset.add));
    }
});

els.cartList.addEventListener("click", event => {
    const inc = event.target.closest("[data-inc]");
    const dec = event.target.closest("[data-dec]");
    const remove = event.target.closest("[data-remove]");

    if (inc) changeQuantity(Number(inc.dataset.inc), 1);
    if (dec) changeQuantity(Number(dec.dataset.dec), -1);
    if (remove) removeFromCart(Number(remove.dataset.remove));
});

els.clearCartButton.addEventListener("click", () => {
    state.cart = [];
    saveCart();
    renderProducts();
    renderCart();
});

els.checkoutButton.addEventListener("click", checkout);
els.refreshButton.addEventListener("click", () => {
    loadStore();
    loadAdminReports();
});
els.productForm.addEventListener("submit", saveProduct);
els.cancelEditButton.addEventListener("click", resetProductForm);
els.pinInput.addEventListener("change", loadAdminReports);
els.lowStockLimit.addEventListener("change", loadAdminReports);
els.loadOrdersButton.addEventListener("click", loadAdminReports);

els.inventoryTable.addEventListener("click", event => {
    const edit = event.target.closest("[data-edit]");
    const remove = event.target.closest("[data-delete]");

    if (edit) editProduct(Number(edit.dataset.edit));
    if (remove) deleteProduct(Number(remove.dataset.delete));
});

els.pinInput.value = state.adminPin;
loadStore().catch(error => showToast(error.message));
