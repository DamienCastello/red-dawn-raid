import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
    selector: 'app-weather-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './weather-modal.component.html',
    styleUrls: ['./weather-modal.component.scss']
})
export class WeatherModalComponent {
    @Input() show = false;
    @Input() weatherBgActive = false;
    @Input() weatherBgImage: string | null = null;

    @Input() isWeatherPreReveal = false;
    @Input() isMeVampire = false;

    // Data
    @Input() weatherRoll: number | null = null;
    @Input() weatherNameFr: string | null | undefined;
    @Input() weatherDescFr: string | null | undefined;
    @Input() weatherStatus: string | null | undefined;

    // Visuals
    @Input() weatherIconTransform = 'rotate(0deg)';
    @Input() weatherIconSrc = '';

    @Output() roll = new EventEmitter<void>();
}
