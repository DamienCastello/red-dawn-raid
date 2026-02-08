import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActionModalVm, ActionModalActions, ActionModalHelpers } from './action-modal.vm';

@Component({
    selector: 'app-action-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './action-modal.component.html',
    styleUrls: ['./action-modal.component.scss']
})
export class ActionModalComponent {
    @Input() vm!: ActionModalVm;
    @Input() actions!: ActionModalActions;
    @Input() helpers!: ActionModalHelpers;

    // Parse une ligne de breakdown PIT au format "hunterId:targetId:location:roll"
    parsePitLine(line: string) {
        const parts = line.split(':');
        if (parts.length < 4) return null;
        return {
            hunterId: parts[0],
            targetId: parts[1],
            location: parts[2],
            roll: parseInt(parts[3], 10)
        };
    }

    getPitGroups() {
        if (!this.vm.breakdownLines) return [];
        const groups: { targetId: string, monsterName: string, results: any[] }[] = [];
        for (const line of this.vm.breakdownLines) {
            const res = this.parsePitLine(line);
            if (res) {
                let g = groups.find(x => x.targetId === res.targetId);
                if (!g) {
                    const monster = this.vm.game?.monsters?.find(m => m.id === res.targetId);
                    const monsterName = monster ? this.getMonsterName(monster.type) : 'le monstre';
                    g = { targetId: res.targetId, monsterName, results: [] };
                    groups.push(g);
                }
                g.results.push(res);
            }
        }
        return groups;
    }

    private getMonsterName(type: string): string {
        switch (type) {
            case 'REVENANT': return 'Revenant';
            case 'BAT': return 'Chauve-souris';
            case 'GARGOYLE': return 'Gargouille';
            case 'WOLF': return 'Loup';
            case 'ABERRATION': return 'Aberration';
            case 'LICHE': return 'Liche';
            default: return 'le monstre';
        }
    }
}
