package ac.cult.cultac.utils.visibility;

import java.util.ArrayList;
import java.util.List;

/**
 * A flat surface p0 + x*u + y*v, with unit u and v, and the regions of it not yet proved hidden. A point of it
 * counts as inside a shadow only {@link #MARGIN} within each of the shadow's planes, far more than doubles can
 * misplace it.
 */
final class SightSurface {
    private static final double MARGIN = 1.0E-8;
    // A region smaller than this is rounding, not something left to see.
    private static final double MIN_AREA = 1.0E-14;

    private final double[] values;
    private final double[][] planes;
    private final double[] p0, u, v;
    private List<SightPolygon> regions = new ArrayList<>(1);

    /** {@code values} and {@code planes} are scratch space, shared by surfaces used one at a time. */
    SightSurface(double[] values, double[][] planes, double[] p0, double[] u, double[] v, SightPolygon whole) {
        this.values = values;
        this.planes = planes;
        this.p0 = p0;
        this.u = u;
        this.v = v;
        regions.add(whole);
    }

    boolean hidden() {
        return regions.isEmpty();
    }

    /**
     * Cuts out of the regions what lies in every one of the shadows: shadow {@code o} is cast from {@code origins[o]}
     * and its {@code counts[o]} rows start at {@code at[o]}.
     */
    void cut(double[][] origins, double[] rows, int[] at, int[] counts) {
        // Only what is left matters, so the shadows are met inside it, or inside its bounds.
        SightPolygon part = regions.size() == 1 ? regions.get(0) : bounds();
        int used = 0;
        for (int o = 0; o < origins.length; o++) {
            double[] origin = origins[o];
            double dx = p0[0] - origin[0], dy = p0[1] - origin[1], dz = p0[2] - origin[2];
            for (int k = 0, row = at[o]; k < counts[o]; k++, row += 4) {
                double a = rows[row] * u[0] + rows[row + 1] * u[1] + rows[row + 2] * u[2];
                double b = rows[row] * v[0] + rows[row + 1] * v[1] + rows[row + 2] * v[2];
                double c = rows[row] * dx + rows[row + 1] * dy + rows[row + 2] * dz + rows[row + 3] + MARGIN;
                SightPolygon next = part.clip(a, b, c, values);
                if (next == null) return;
                // Only a plane that cut the part can bound it.
                if (next != part) {
                    // More planes than there is room for: this cut is left out, which only leaves more unproved.
                    if (used == planes.length) return;
                    planes[used][0] = a;
                    planes[used][1] = b;
                    planes[used++][2] = c;
                    part = next;
                }
            }
        }
        if (used == 0) {
            regions = List.of();
            return;
        }
        List<SightPolygon> next = new ArrayList<>();
        for (SightPolygon region : regions) {
            SightPolygon rest = region;
            for (int k = 0; k < used && rest != null; k++) {
                double[] plane = planes[k];
                SightPolygon outside = rest.clip(-plane[0], -plane[1], -plane[2], values);
                if (outside != null && outside.area() > MIN_AREA) next.add(outside);
                rest = rest.clip(plane[0], plane[1], plane[2], values);
            }
        }
        regions = next;
    }

    /** The rectangle around the regions, as min x, min y, max x, max y. */
    double[] extent() {
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (SightPolygon region : regions) {
            for (int i = 0; i < region.size; i++) {
                minX = Math.min(minX, region.x[i]);
                maxX = Math.max(maxX, region.x[i]);
                minY = Math.min(minY, region.y[i]);
                maxY = Math.max(maxY, region.y[i]);
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    private SightPolygon bounds() {
        double[] extent = extent();
        return SightPolygon.rectangle(extent[0], extent[1], extent[2], extent[3]);
    }
}
