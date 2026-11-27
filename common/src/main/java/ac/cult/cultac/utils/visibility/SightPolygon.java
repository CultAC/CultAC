package ac.cult.cultac.utils.visibility;

/** A convex polygon in doubles, its corners in order around it. */
final class SightPolygon {
    final double[] x, y;
    int size;

    private SightPolygon(int capacity) {
        x = new double[capacity];
        y = new double[capacity];
    }

    static SightPolygon rectangle(double minX, double minY, double maxX, double maxY) {
        SightPolygon polygon = new SightPolygon(4);
        polygon.add(minX, minY);
        polygon.add(maxX, minY);
        polygon.add(maxX, maxY);
        polygon.add(minX, maxY);
        return polygon;
    }

    private void add(double px, double py) {
        if (size > 0 && x[size - 1] == px && y[size - 1] == py) return;
        x[size] = px;
        y[size++] = py;
    }

    /**
     * The part where a*x + b*y + c <= 0: this polygon itself when all of it is, null when no area of it is. The part
     * on the other side, clipped with the negated plane, shares this part's new corners exactly. {@code values} is
     * scratch space.
     */
    SightPolygon clip(double a, double b, double c, double[] values) {
        double[] v = size <= values.length ? values : new double[size];
        boolean in = false, out = false;
        for (int i = 0; i < size; i++) {
            v[i] = a * x[i] + b * y[i] + c;
            if (v[i] <= 0) in = true;
            else out = true;
        }
        if (!out) return this;
        if (!in) return null;
        SightPolygon result = new SightPolygon(size + 2);
        for (int i = 0, j = size - 1; i < size; j = i++) {
            if ((v[j] <= 0) != (v[i] <= 0)) {
                double t = v[j] / (v[j] - v[i]);
                result.add(x[j] + t * (x[i] - x[j]), y[j] + t * (y[i] - y[j]));
            }
            if (v[i] <= 0) result.add(x[i], y[i]);
        }
        int last = result.size - 1;
        if (last > 0 && result.x[0] == result.x[last] && result.y[0] == result.y[last]) result.size--;
        return result.size < 3 ? null : result;
    }

    double area() {
        double sum = 0;
        for (int i = 0, j = size - 1; i < size; j = i++) sum += x[j] * y[i] - x[i] * y[j];
        return Math.abs(sum) / 2;
    }
}
