import { Injectable } from '@angular/core';

/**
 * Helpers d'affichage PURS et PARTAGÉS : à partir d'un code métier
 * (action, potion, lieu, météo, mod…) ils renvoient un chemin d'image ou
 * un libellé FR. Aucune dépendance à l'état de la partie — tout passe par
 * les paramètres. Injecté par game.component et par les composants de board
 * pour éviter de dupliquer ces tables de correspondance.
 */
@Injectable({ providedIn: 'root' })
export class GameAssetsService {

    private readonly HUNTER_ACTIONS_DIR = '/assets/cards/hunter_actions/';
    private readonly VAMP_ACTIONS_DIR = '/assets/cards/vampire_actions/';
    private readonly POTIONS_DIR = '/assets/cards/potions/';
    private readonly ELIXIRS_DIR = '/assets/cards/elixirs/';

    readonly potionBackSrc = '/assets/cards/potion_verso.png';

    private readonly HUNTER_CODES = [
        'EAU_BENITE', 'FUMIGATION_AIL', 'PISTEUR', 'FEU_DE_CAMP',
        'NET', 'PIT', 'PROVOCATION', 'INCENDIAIRE', 'AMBUSH',
        'LONELY', 'BLESSED_STAKE', 'SACRED_ROSARY', 'CHARISMATIQUE',
        'MARCHAND_ITINERANT', 'MARCHAND_BONUS_BUY', 'CRATE_LAKE', 'CRATE_MANOR'
    ];
    private readonly VAMP_CODES = [
        'CATACLYSME', 'CLONES_OMBRE', 'IMAGE_MIROIR', 'PRESENCE_ECRASANTE',
        'ECLIPSE', 'BLOOD_MOON', 'VOILE_DE_BRUME', 'FAIM_IRREPRESSIBLE',
        'MARQUE_TENEBREUSE', 'AFFAIBLISSEMENT_OCCULTE', 'PASSAGE_SECRET',
        'AVIDITE_NOCTURNE', 'IMAGE_MIROIR_SETUP', 'IMAGE_MIROIR_RESOLVE',
        'ADVANCED_TRANSMUTATION', 'ADVANCED_TRANSMUTATION_BUY'
    ];

    // ---------- Tiers (miroir du back) ----------

    diceToTier(d?: string): 0 | 1 | 2 | 3 {
        switch (d) {
            case 'D6': return 1;
            case 'D8': return 2;
            case 'D12': return 3;
            default: return 0;
        }
    }

    hunterWeaponType(weapon?: string): 'BLEED' | 'STUN' | 'RANGE' {
        const w = weapon || '';
        if (w.includes('MACE') || w.includes('HAMMER') || w.includes('FLAIL')) return 'STUN';
        if (w.includes('SPEAR') || w.includes('CROSSBOW') || w.includes('PISTOL')) return 'RANGE';
        return 'BLEED';
    }

    // ---------- Équipement ----------

    weaponImg(p: any): string {
        const tier = this.diceToTier(p?.attackDice);
        const isVamp = p?.role === 'VAMPIRE' || p?.role === 'SERVANT';

        if (isVamp) {
            return `/assets/cards/stuff/W_T${tier}_VAMP.png`;
        }
        if (tier === 0) {
            return `/assets/cards/stuff/W_T0_HUNTER.png`;
        }
        const type = this.hunterWeaponType(p?.weapon);
        return `/assets/cards/stuff/W_T${tier}_${type}_HUNTER.png`;
    }

    armorImg(p: any): string {
        const tier = this.diceToTier(p?.defenseDice);
        const isVamp = p?.role === 'VAMPIRE' || p?.role === 'SERVANT';
        return isVamp
            ? `/assets/cards/stuff/A_T${tier}_VAMP.png`
            : `/assets/cards/stuff/A_T${tier}_HUNTER.png`;
    }

    /** Icône de cœur (PV) selon le rôle du joueur. */
    heartIconFor(p: any): string {
        const role = (p?.role || '').toUpperCase();
        if (role === 'SERVANT') return `/assets/icons/VAMPIRE-hearth.png`;
        return `/assets/icons/${role}-hearth.png`;
    }

    stuffImg(file: string): string {
        return `/assets/cards/stuff/${file}`;
    }

    // ---------- Cartes action ----------

    /** Action -> image. `role` désambiguïse quand le code est générique. */
    actionImg(code: string | null | undefined, role: string | null | undefined = undefined): string {
        if (!code) return '';
        let base;
        if (this.HUNTER_CODES.includes(code)) {
            base = this.HUNTER_ACTIONS_DIR;
        } else if (this.VAMP_CODES.includes(code)) {
            base = this.VAMP_ACTIONS_DIR;
        } else if (role === 'HUNTER') {
            base = this.HUNTER_ACTIONS_DIR;
        } else if (role === 'VAMPIRE' || role === 'SERVANT') {
            base = this.VAMP_ACTIONS_DIR;
        } else {
            base = this.HUNTER_ACTIONS_DIR;
        }
        return base + this.actionFile(code);
    }

    /** Map des exceptions + fallback auto. */
    actionFile(code: string): string {
        switch (code) {
            // --- HUNTER ---
            case 'EAU_BENITE': return 'eau_benite.png';
            case 'FUMIGATION_AIL': return 'fumigation_ail.png';
            case 'PISTEUR': return 'pistage.png';
            case 'FEU_DE_CAMP': return 'feu_de_camp.png';
            case 'NET': return 'net.png';
            case 'PIT': return 'pit.png';
            case 'PROVOCATION': return 'provocation.png';
            case 'INCENDIAIRE': return 'incendiaire.png';
            case 'AMBUSH': return 'ambush.png';
            case 'LONELY': return 'lonely.png';
            case 'BLESSED_STAKE': return 'blessed_stake.png';
            case 'SACRED_ROSARY': return 'sacred_rosary.png';
            case 'CHARISMATIQUE': return 'charismatique.png';
            case 'MARCHAND_ITINERANT':
            case 'MARCHAND_BONUS_BUY':
                return 'marchand_itinerant.png';
            case 'CRATE_LAKE': return 'crate_lake.png';
            case 'CRATE_MANOR': return 'crate-manor.png';

            // --- VAMPIRE ---
            case 'AFFAIBLISSEMENT_OCCULTE': return 'affaiblissement_occulte.png';
            case 'CLONES_OMBRE': return 'clones_ombre.png';
            case 'MARQUE_TENEBREUSE': return 'marque_tenebreuse.png';
            case 'AVIDITE_NOCTURNE': return 'avidite_nocturne.png';
            case 'ECLIPSE': return 'eclipse.png';
            case 'PASSAGE_SECRET': return 'passage_secret.png';
            case 'BLOOD_MOON': return 'blood_moon.png';
            case 'FAIM_IRREPRESSIBLE': return 'faim_irrepressible.png';
            case 'PRESENCE_ECRASANTE': return 'presence_ecrasante.png';
            case 'CATACLYSME': return 'cataclysme.png';
            case 'VOILE_DE_BRUME': return 'voile_de_brume.png';
            case 'ADVANCED_TRANSMUTATION':
            case 'ADVANCED_TRANSMUTATION_BUY':
                return 'advanced_transmutation.png';
            case 'IMAGE_MIROIR':
            case 'IMAGE_MIROIR_SETUP':
            case 'IMAGE_MIROIR_RESOLVE':
                return 'image_miroir.png';

            default:
                return code.toLowerCase() + '.png';
        }
    }

    actionBackSrc(p: any): string {
        return p?.role === 'VAMPIRE'
            ? '/assets/cards/vampire_verso.png'
            : '/assets/cards/hunter_verso.png';
    }

    /** Fond CSS (url(...)) d'une modale d'action selon son mode. */
    actionBackgroundSrc(mode: string | null | undefined): string {
        if (mode === 'NET') return 'url(/assets/actions/net.png)';
        if (mode === 'PIT') return 'url(/assets/actions/traphole.png)';
        if (mode === 'INCENDIAIRE') return 'url(/assets/actions/burn.png)';
        if (mode === 'PROVOCATION') return 'url(/assets/actions/taunt.png)';
        if (mode === 'AMBUSH') return 'url(/assets/actions/ambush.png)';
        if (mode === 'LONELY') return 'url(/assets/actions/lonely.png)';
        if (mode === 'BLESSED_STAKE') return 'url(/assets/actions/blessed_stake.png)';
        if (mode === 'CHARISMATIQUE') return 'url(/assets/actions/charismatic.png)';
        if (mode === 'MARCHAND_ITINERANT' || mode === 'MARCHAND_BONUS_BUY') return 'url(/assets/actions/traveling_merchant.png)';
        if (mode === 'ADVANCED_TRANSMUTATION' || mode === 'ADVANCED_TRANSMUTATION_BUY') return 'url(/assets/actions/advanced_transmutation.png)';
        if (mode === 'PRESENCE_ECRASANTE') return 'url(/assets/actions/overwhelming_presence.png)';
        if (mode === 'CATACLYSME') return 'url(/assets/actions/cataclysm.png)';
        if (mode === 'CLONES_OMBRE') return 'url(/assets/actions/shadow_clones.png)';
        if (mode === 'IMAGE_MIROIR_SETUP' || mode === 'IMAGE_MIROIR_RESOLVE') return 'url(/assets/actions/miror_image.png';
        if (mode === 'ECLIPSE') return 'url(/assets/actions/eclipse.png';
        if (mode === 'BLOOD_MOON') return 'url(/assets/actions/redmoon.png';
        if (mode === 'VOILE_DE_BRUME') return 'url(/assets/actions/veil_of_mist.png';
        if (mode === 'FAIM_IRREPRESSIBLE') return 'url(/assets/actions/irrepressible_hunger.png';
        if (mode === 'MARQUE_TENEBREUSE') return 'url(/assets/actions/dark_mark.png';
        if (mode === 'AFFAIBLISSEMENT_OCCULTE') return 'url(/assets/actions/occult_weakening.png';
        if (mode === 'PASSAGE_SECRET') return 'url(/assets/actions/secret_passage.png';
        if (mode === 'AVIDITE_NOCTURNE') return 'url(/assets/actions/nocturnal_greed.png)';
        if (mode === 'EAU_BENITE') return 'url(/assets/actions/holy_water.png';
        if (mode === 'CRATE_LAKE') return 'url(/assets/actions/crate-lake.png)';
        if (mode === 'CRATE_MANOR') return 'url(/assets/actions/crate-manor.png)';
        return '';
    }

    // ---------- Potions / élixirs ----------

    potionImg(code: string | null | undefined): string {
        if (!code) return '';
        return this.POTIONS_DIR + code.toLowerCase() + '.png';
    }

    elixirImg(code: string | null | undefined): string {
        if (!code) return '';
        return this.ELIXIRS_DIR + code.toLowerCase() + '.png';
    }

    deckBackFor(kind: 'HUNTER_ACTIONS' | 'VAMP_ACTIONS' | 'POTIONS' | 'ELIXIRS'): string {
        switch (kind) {
            case 'HUNTER_ACTIONS': return '/assets/cards/hunter_verso.png';
            case 'VAMP_ACTIONS': return '/assets/cards/vampire_verso.png';
            case 'POTIONS': return this.potionBackSrc;
            case 'ELIXIRS': return this.potionBackSrc;
        }
    }

    /** Image de défausse (dernière carte) pour un type de pile. */
    discardImgFor(kind: 'HUNTER_ACTIONS' | 'VAMP_ACTIONS' | 'POTIONS' | 'ELIXIRS', lastDiscardId: string | null | undefined): string {
        if (!lastDiscardId) return '';
        switch (kind) {
            case 'HUNTER_ACTIONS': return this.HUNTER_ACTIONS_DIR + this.actionFile(lastDiscardId);
            case 'VAMP_ACTIONS': return this.VAMP_ACTIONS_DIR + this.actionFile(lastDiscardId);
            case 'POTIONS': return this.potionImg(lastDiscardId);
            case 'ELIXIRS': return this.elixirImg(lastDiscardId);
        }
    }

    // ---------- Lieux / météo / mods ----------

    infraImg(code: string): string {
        return `/assets/cards/locations/${code.toLowerCase()}.png`;
    }

    weatherIconSrc(ws?: string | null): string {
        if (!ws) return '';
        if (ws.includes('WIND')) return `/assets/weather/icon-wind.png`;
        if (ws.includes('BLOOD_MOON')) return `/assets/weather/icon-red_moon.png`;
        return `/assets/weather/icon-${ws.toLowerCase()}.png`;
    }

    modIconSrc(source: string): string {
        const fallback = '/assets/icons/action-hunter-icon.png';
        if (!source) return fallback;

        if (source.startsWith('ACTION:')) {
            const parts = source.split(':');
            const code = parts[1] || '';
            const hunterActions = ['NET', 'PIT', 'PROVOCATION', 'AMBUSH', 'LONELY', 'CRATE_LAKE', 'CRATE_MANOR'];
            const isHunter = hunterActions.includes(code);

            if (code === 'BLESSED_STAKE') return '/assets/icons/HUNTER-sword.png';
            if (code === 'SACRED_ROSARY') return '/assets/icons/HUNTER-armor.png';

            return isHunter
                ? '/assets/icons/action-hunter-icon.png'
                : '/assets/icons/action-vampire-icon.png';
        }

        if (source.startsWith('EQUIP:')) {
            const parts = source.split(':');
            const type = (parts[1] || '').toUpperCase();

            if (type === 'BLEED_WEAPON' || type === 'STUN_WEAPON' || type === 'RANGED_WEAPON') {
                return '/assets/icons/HUNTER-sword.png';
            }
            if (type === 'HUNTER_ARMOR') return '/assets/icons/HUNTER-armor.png';
            if (type === 'VAMPIRE_WEAPON') return '/assets/icons/VAMPIRE-sword.png';
            if (type === 'VAMPIRE_ARMOR' || type === 'VAMPIRE_ARMOR_T3') return '/assets/icons/VAMPIRE-armor.png';
            return fallback;
        }

        if (source.startsWith('HIT:BLEED_WEAPON')) return '/assets/icons/bleed.png';
        if (source.startsWith('HIT:STUN_WEAPON')) return '/assets/icons/stun.png';
        if (source.startsWith('HIT:RANGED_WEAPON')) return '/assets/icons/range.png';

        return fallback;
    }

    // ---------- Libellés FR ----------

    potionLabelFr(id: string): string {
        switch (id) {
            case 'FORCE': return 'Potion de force';
            case 'ENDURANCE': return 'Potion d’endurance';
            case 'VIE': return 'Potion de vie';
            default: return id;
        }
    }

    actionLabelFr(mode: string | null | undefined): string {
        if (!mode) return '';
        switch (mode) {
            case 'EAU_BENITE': return 'Eau bénite';
            case 'FUMIGATION_AIL': return 'Fumigation d\'ail';
            case 'PISTEUR': return 'Pisteur';
            case 'FEU_DE_CAMP': return 'Feu de camp';
            case 'NET': return 'Filet';
            case 'PIT': return 'Fosse';
            case 'PROVOCATION': return 'Provocation';
            case 'INCENDIAIRE': return 'Incendiaire';
            case 'AMBUSH': return 'Embuscade';
            case 'LONELY': return 'Solitaire';
            case 'BLESSED_STAKE': return 'Pieu béni';
            case 'SACRED_ROSARY': return 'Chapelet sacré';
            case 'CHARISMATIQUE': return 'Charismatique';
            case 'MARCHAND_ITINERANT':
            case 'MARCHAND_BONUS_BUY': return 'Marchand itinérant';
            case 'ADVANCED_TRANSMUTATION':
            case 'ADVANCED_TRANSMUTATION_BUY': return 'Transmutation avancée';
            case 'CRATE_LAKE': return 'Caisse : Lac';
            case 'CRATE_MANOR': return 'Caisse : Manoir';
            case 'PRESENCE_ECRASANTE': return 'Présence écrasante';
            case 'CATACLYSME': return 'Cataclysme';
            case 'CLONES_OMBRE': return 'Clones d’ombre';
            case 'IMAGE_MIROIR':
            case 'IMAGE_MIROIR_SETUP':
            case 'IMAGE_MIROIR_RESOLVE': return 'Image miroir';
            case 'ECLIPSE': return 'Éclipse';
            case 'BLOOD_MOON': return 'Lune sanglante';
            case 'VOILE_DE_BRUME': return 'Voile de brume';
            case 'FAIM_IRREPRESSIBLE': return 'Faim irrépressible';
            case 'MARQUE_TENEBREUSE': return 'Marque ténébreuse';
            case 'AFFAIBLISSEMENT_OCCULTE': return 'Affaiblissement occulte';
            case 'PASSAGE_SECRET': return 'Passage secret';
            case 'AVIDITE_NOCTURNE': return 'Avidité nocturne';
            default: return '';
        }
    }

    /** Libellé FR d'une ressource ("wood" -> "bois"). */
    resLabelFr(k: string): string {
        switch (k) {
            case 'wood': return 'bois';
            case 'herbs': return 'herbes';
            case 'stone': return 'pierre';
            case 'iron': return 'fer';
            case 'water': return 'eau';
            case 'gold': return 'or';
            case 'souls': return 'âmes';
            case 'silver': return 'argent';
            default: return k;
        }
    }
}
