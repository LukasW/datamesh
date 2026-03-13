/**
 * Policy modal helpers – loaded as a static file to avoid Qute template-expression
 * parsing ({...} in JavaScript would be mis-interpreted by the Qute parser).
 *
 * Uses event delegation on document.body so that dynamically loaded htmx content
 * (the modal fragment) is handled without needing re-initialisation.
 */
(function () {
    'use strict';

    // ── Partner-Picker: "Partner suchen" button ───────────────────────────────
    // The button (#partner-suche-btn) lives inside the htmx-swapped modal fragment.
    // ── Partner-Picker: "Partner suchen" button ───────────────────────────────
    document.body.addEventListener('click', function (e) {
        var btn = e.target.closest('#partner-suche-btn');
        if (btn) {
            htmx.ajax('GET', '/policen/fragments/partner-suche', {
                target: '#partner-suche-container',
                swap: 'innerHTML'
            });
        }
    });

    // ── Partner-Picker: result item selected ─────────────────────────────────
    // List items carry data-partner-id / data-partner-name set by Qute.
    // No htmx round-trip needed — update the picker entirely in JS.
    document.body.addEventListener('click', function (e) {
        var item = e.target.closest('[data-partner-id]');
        if (!item) return;

        var partnerId   = item.getAttribute('data-partner-id');
        var partnerName = item.getAttribute('data-partner-name');
        var picker = document.getElementById('partner-picker');
        if (!picker) return;

        // Fill the hidden form field
        picker.querySelector('[name=partnerId]').value = partnerId;

        // Update the readonly display input
        var display = picker.querySelector('input[type=text]');
        if (display) {
            display.value = partnerName;
            display.placeholder = 'Partner gewählt';
        }

        // Show "ID: …" hint (create once, update on every selection)
        var idHint = picker.querySelector('small.partner-id-hint');
        if (!idHint) {
            idHint = document.createElement('small');
            idHint.className = 'text-muted partner-id-hint';
            var container = document.getElementById('partner-suche-container');
            picker.insertBefore(idHint, container);
        }
        idHint.textContent = 'ID: ' + partnerId;

        // Change button label to "Ändern"
        var searchBtn = document.getElementById('partner-suche-btn');
        if (searchBtn) searchBtn.textContent = '\uD83D\uDD0D \u00C4ndern';

        // Close the search panel
        var panel = document.getElementById('partner-suche-container');
        if (panel) panel.innerHTML = '';
    });

    // ── New-policy form validation ────────────────────────────────────────────
    // Called from onsubmit="return validatePolicyNeuForm(this)" on the form.
    window.validatePolicyNeuForm = function (form) {
        var pid = form.querySelector('[name=partnerId]').value;
        if (!pid) {
            document.getElementById('policy-form-errors').innerHTML =
                '<div class="alert alert-danger">Bitte einen Partner auswählen.</div>';
            return false;
        }
        return true;
    };
}());

