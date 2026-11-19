<script lang="ts" module>
	/** One stroke style for every icon: 24-unit grid, 1.5 stroke, round joins. Add paths here, nowhere else. */
	const PATHS = {
		sidebar: 'M4 5h16v14H4zM9 5v14',
		inspector: 'M4 5h16v14H4zM15 5v14',
		plus: 'M12 5v14M5 12h14',
		search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-3.5-3.5',
		settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4',
		x: 'M6 6l12 12M18 6L6 18',
		'chevron-right': 'M9 6l6 6-6 6',
		'chevron-left': 'M15 6l-6 6 6 6',
		'chevron-down': 'M6 9l6 6 6-6',
		'arrow-up': 'M12 19V5M6 11l6-6 6 6',
		'arrow-down': 'M12 5v14M6 13l6 6 6-6',
		stop: 'M7 7h10v10H7z',
		more: 'M5 12h.01M12 12h.01M19 12h.01',
		folder: 'M4 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z',
		external: 'M14 5h5v5M19 5l-8 8M18 14v4a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h4',
		trash: 'M5 7h14M10 7V5h4v2M7 7l1 12h8l1-12',
		copy: 'M9 9h10v10H9zM5 15V5h10',
		check: 'M5 12.5l4.5 4.5L19 7.5',
		alert: 'M12 4l9 16H3zM12 10v4M12 17h.01',
		dot: 'M12 12h.01',
		sun: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4',
		moon: 'M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z',
		monitor: 'M4 5h16v11H4zM9 20h6M12 16v4',
		pencil: 'M5 19l1-4 10-10 3 3-10 10zM14 7l3 3'
	} as const;
	export type IconName = keyof typeof PATHS;
</script>

<script lang="ts">
	let { name, size = 16, label }: { name: IconName; size?: number; label?: string } = $props();
</script>

<svg
	class="icon"
	width={size}
	height={size}
	viewBox="0 0 24 24"
	fill="none"
	stroke="currentColor"
	stroke-width={name === 'more' || name === 'dot' ? 3 : 1.5}
	stroke-linecap="round"
	stroke-linejoin="round"
	role={label ? 'img' : undefined}
	aria-label={label}
	aria-hidden={label ? undefined : 'true'}
>
	<path d={PATHS[name]} />
</svg>

<style>
	.icon {
		flex: none;
		display: block;
	}
</style>
