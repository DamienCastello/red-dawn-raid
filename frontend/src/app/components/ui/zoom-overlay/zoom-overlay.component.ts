import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
    selector: 'app-zoom-overlay',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './zoom-overlay.component.html',
    styleUrls: ['./zoom-overlay.component.scss']
})
export class ZoomOverlayComponent {
    @Input() show = false;

    // Zoom positioning & dimensions
    @Input() zoomStyle: any = null;
    @Input() zoomMediaW = 0;
    @Input() zoomMediaH = 0;

    // Media content
    @Input() zoomSrc = '';

    // Badge
    @Input() zoomBadgeOn = false;
    @Input() zoomBadgeIsHunter: boolean | undefined;
    @Input() zoomBadgeText = '';

    // Info Panel
    @Input() zoomInfoOn = false;
    @Input() zoomInfoKey: string | null = null;

    // Standard info lines
    @Input() zoomInfoLines: string[] = [];
    @Input() hasZoomInfoLines = false;

    // Altar specific info
    @Input() zoomInfoLinesHunter: string[] = [];
    @Input() zoomInfoLinesVamp: string[] = [];
    @Input() zoomInfoNote = '';

    // Forge specific info
    @Input() forgeCostGroups: any = null;
}
